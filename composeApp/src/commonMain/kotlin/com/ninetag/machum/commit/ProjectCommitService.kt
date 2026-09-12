package com.ninetag.machum.commit

import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.createFolder
import com.ninetag.machum.external.isValidProjectFolderName
import com.ninetag.machum.entity.effectiveAutoTags
import com.ninetag.machum.entity.normalizeTag
import com.ninetag.machum.entity.normalizeTags
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class ProjectCommitService private constructor(
    private val fileManager: FileManager,
    private val workspaceMutator: CommitWorkspaceMutator,
) {
    constructor(fileManager: FileManager) : this(
        fileManager = fileManager,
        workspaceMutator = FileManagerCommitWorkspaceMutator(fileManager),
    )

    @Suppress("UNUSED_PARAMETER")
    internal constructor(
        fileManager: FileManager,
        workspaceMutator: CommitWorkspaceMutator,
        testing: Unit = Unit,
    ) : this(fileManager = fileManager, workspaceMutator = workspaceMutator)

    private val commitMutex = Mutex()

    suspend fun preview(project: PlatformFile): CommitPreview = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            prepare(project).preview
        }
    }

    suspend fun history(project: PlatformFile, limit: Int = 50): List<CommitHistoryEntry> =
        withContext(Dispatchers.IO) {
            require(limit > 0) { "limit must be positive" }
            commitMutex.withLock {
                val store = FileCommitStore(fileManager, project)
                val history = mutableListOf<CommitHistoryEntry>()
                val visited = mutableSetOf<String>()
                var current = store.loadHead()
                while (current != null && history.size < limit) {
                    if (!visited.add(current.id)) {
                        throw CommitStorageException("커밋 parent 연결에 순환이 있습니다: ${current.id}")
                    }
                    val tree = store.loadTree(current.treeHash).trackedContentTree()
                    val parent = current.parentId?.let { store.loadCommit(it) }
                    val parentTree = parent?.let { store.loadTree(it.treeHash).trackedContentTree() }
                    val changes = enrichChanges(
                        store = store,
                        changes = CommitPlanner.changes(parentTree, tree),
                        currentBlobs = null,
                    )
                    history += CommitHistoryEntry(current, changes)
                    current = parent
                }
                history
            }
        }

    suspend fun diff(
        project: PlatformFile,
        commitId: String?,
        fileId: String,
    ): FileLineDiff = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            val store = FileCommitStore(fileManager, project)
            val currentTree: CommitTree
            val previousTree: CommitTree?
            val currentBlobs: Map<String, String>?
            if (commitId == null) {
                val head = store.loadHead()
                previousTree = head?.let { store.loadTree(it.treeHash).trackedContentTree() }
                val snapshot = scan(project)
                currentTree = snapshot.tree
                currentBlobs = snapshot.blobs
            } else {
                val commit = store.loadCommit(commitId)
                currentTree = store.loadTree(commit.treeHash).trackedContentTree()
                previousTree = commit.parentId
                    ?.let { store.loadCommit(it) }
                    ?.let { store.loadTree(it.treeHash).trackedContentTree() }
                currentBlobs = null
            }

            val change = CommitPlanner.changes(previousTree, currentTree)
                .find { it.fileId == fileId }
                ?: throw IllegalArgumentException("선택한 커밋에서 파일 변경을 찾을 수 없습니다.")
            val oldContent = change.oldBlobHash?.let { store.loadBlob(it) }
            val newContent = change.newBlobHash?.let { blobHash ->
                currentBlobs?.get(blobHash) ?: store.loadBlob(blobHash)
            }
            LineDiffEngine.build(change, oldContent, newContent)
        }
    }

    suspend fun restore(
        project: PlatformFile,
        commitId: String,
        expectedWorkingTreeHash: String,
    ): RestoreResult =
        withContext(Dispatchers.IO) {
            commitMutex.withLock {
                val prepared = prepare(project)
                if (prepared.head == null) {
                    throw IllegalStateException("복구할 커밋 이력이 없습니다.")
                }
                requireExpectedWorkingTree(prepared.snapshot, expectedWorkingTreeHash)

                val store = FileCommitStore(fileManager, project)
                restorePreparedSnapshot(
                    project = project,
                    store = store,
                    targetCommit = store.loadCommit(commitId),
                    rollbackSnapshot = prepared.snapshot,
                    failureMessage = "선택한 커밋을 복구하지 못했습니다.",
                )
            }
        }

    /**
     * 현재 HEAD snapshot을 working tree에 다시 적용한다.
     * 일반 미커밋 변경을 의도적으로 폐기하는 동작이므로 확인 화면에서 캡처한 hash가 반드시 일치해야 한다.
     */
    suspend fun restoreHeadSnapshot(
        project: PlatformFile,
        commitId: String,
        expectedWorkingTreeHash: String,
    ): RestoreResult = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            val prepared = prepare(project)
            val head = prepared.head ?: throw IllegalStateException("복구할 커밋 이력이 없습니다.")
            require(head.id == commitId) { "현재 커밋 시점만 이 방식으로 복원할 수 있습니다." }
            requireExpectedWorkingTree(prepared.snapshot, expectedWorkingTreeHash)

            restorePreparedSnapshot(
                project = project,
                store = FileCommitStore(fileManager, project),
                targetCommit = head,
                rollbackSnapshot = prepared.snapshot,
                failureMessage = "현재 커밋 시점으로 복구하지 못했습니다.",
            )
        }
    }

    /** 현재 HEAD 커밋의 부모 snapshot을 working tree에 적용하고 HEAD와 이력은 유지한다. */
    suspend fun revertHead(
        project: PlatformFile,
        commitId: String,
        expectedWorkingTreeHash: String,
    ): RestoreResult = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            val prepared = prepare(project)
            val head = prepared.head ?: throw IllegalStateException("되돌릴 커밋 이력이 없습니다.")
            require(head.id == commitId) { "현재 커밋의 변경만 되돌릴 수 있습니다." }
            val parentId = head.parentId
                ?: throw IllegalStateException("이 커밋보다 이전 기준점이 없습니다.")
            requireExpectedWorkingTree(prepared.snapshot, expectedWorkingTreeHash)

            val store = FileCommitStore(fileManager, project)
            restorePreparedSnapshot(
                project = project,
                store = store,
                targetCommit = store.loadCommit(parentId),
                rollbackSnapshot = prepared.snapshot,
                failureMessage = "최근 커밋의 변경을 되돌리지 못했습니다.",
            )
        }
    }

    /** 공개 API가 대상과 확인 시점의 작업 트리를 검증한 뒤 같은 snapshot 적용 경로를 사용한다. */
    private suspend fun restorePreparedSnapshot(
        project: PlatformFile,
        store: FileCommitStore,
        targetCommit: ProjectCommit,
        rollbackSnapshot: ProjectSnapshot,
        failureMessage: String,
    ): RestoreResult {
        val target = loadRestoreSnapshot(
            store = store,
            tree = store.loadTree(targetCommit.treeHash).trackedContentTree(),
            requiredProjectTag = normalizeTag(project.name),
        )
        val targetHash = target.workingTreeHash()
        val changedFiles = CommitPlanner.changes(rollbackSnapshot.tree, target.tree).size
        if (targetHash != rollbackSnapshot.workingTreeHash()) {
            applyRestoreTransaction(
                project = project,
                rollbackSnapshot = rollbackSnapshot,
                resultWorkingTreeHash = targetHash,
                failureMessage = failureMessage,
                mutation = { applySnapshot(project, target.tree, target.blobs) },
                rollback = { applySnapshot(project, rollbackSnapshot.tree, rollbackSnapshot.blobs) },
            )
        }
        return RestoreResult(targetCommit, changedFiles, targetHash)
    }

    /**
     * 선택한 역사 버전의 본문과 사용자 metadata를 같은 fileId의 현재 경로에 적용한다.
     * 현재 파일의 이름·직속 폴더와 배치 정체성(id·plot)은 바꾸지 않는다.
     */
    suspend fun restoreFileContent(
        project: PlatformFile,
        commitId: String,
        fileId: String,
        side: CommitFileSide,
        expectedWorkingTreeHash: String,
    ): FileRestoreResult = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            val prepared = prepareFileRestore(
                project = project,
                commitId = commitId,
                fileId = fileId,
                side = side,
                expectedWorkingTreeHash = expectedWorkingTreeHash,
            )
            val target = prepared.targetState
                ?: throw IllegalArgumentException(
                    "선택한 쪽에는 파일이 없습니다. 단일 파일 전체 복원을 사용해 주세요.",
                )
            val current = prepared.current
                ?: throw IllegalArgumentException(
                    "현재 파일이 없습니다. 단일 파일 전체 복원을 사용해 주세요.",
                )
            val historicalContent = loadHistoricalContent(
                store = prepared.store,
                state = target,
                expectedFileId = fileId,
            )
            val projectConfig = fileManager.projectConfig.value
            val currentFolderPath = FileKey.of(current.relativePath).folder.relativePath
            val currentManagedTags = projectConfig
                ?.effectiveAutoTags(currentFolderPath)
                .orEmpty()
                .toSet()
            val allManagedTags = projectConfig
                ?.folders
                ?.values
                ?.flatMap { folder -> folder.autoTags }
                .orEmpty()
                .map(::normalizeTag)
                .toSet()
            val targetContent = mergeHistoricalContentAtCurrentPlacement(
                historicalContent = historicalContent,
                currentContent = current.content,
                expectedFileId = fileId,
                requiredProjectTag = normalizeTag(project.name),
                currentManagedTags = currentManagedTags,
                allManagedTags = allManagedTags,
            )
            val restoredWorkingTreeHash = prepared.snapshot.tree
                .withFileEntry(
                    fileId = fileId,
                    relativePath = current.relativePath,
                    blobHash = sha256Utf8(targetContent),
                )
                .workingTreeHash()
            if (current.content == targetContent) {
                return@withLock FileRestoreResult(
                    fileId = fileId,
                    side = side,
                    previousPath = current.relativePath,
                    restoredPath = current.relativePath,
                    changed = false,
                    workingTreeHash = restoredWorkingTreeHash,
                )
            }

            applyRestoreTransaction(
                project = project,
                rollbackSnapshot = prepared.snapshot,
                resultWorkingTreeHash = restoredWorkingTreeHash,
                failureMessage = "선택한 파일 내용을 복원하지 못했습니다: ${current.relativePath}",
                mutation = { workspaceMutator.write(current.platformFile, targetContent) },
                rollback = { restoreOriginalFile(project, current) },
            )
            FileRestoreResult(
                fileId = fileId,
                side = side,
                previousPath = current.relativePath,
                restoredPath = current.relativePath,
                changed = true,
                workingTreeHash = restoredWorkingTreeHash,
            )
        }
    }

    /** 선택한 역사 버전의 존재 여부, 전체 Markdown과 상대 경로를 한 fileId에만 적용한다. */
    suspend fun restoreFile(
        project: PlatformFile,
        commitId: String,
        fileId: String,
        side: CommitFileSide,
        expectedWorkingTreeHash: String,
    ): FileRestoreResult = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            val prepared = prepareFileRestore(
                project = project,
                commitId = commitId,
                fileId = fileId,
                side = side,
                expectedWorkingTreeHash = expectedWorkingTreeHash,
            )
            val current = prepared.current
            val target = prepared.targetState

            if (target == null) {
                val restoredWorkingTreeHash = prepared.snapshot.tree
                    .withFileEntry(fileId = fileId, relativePath = null, blobHash = null)
                    .workingTreeHash()
                if (current == null) {
                    return@withLock FileRestoreResult(
                        fileId = fileId,
                        side = side,
                        previousPath = null,
                        restoredPath = null,
                        changed = false,
                        workingTreeHash = restoredWorkingTreeHash,
                    )
                }
                applyRestoreTransaction(
                    project = project,
                    rollbackSnapshot = prepared.snapshot,
                    resultWorkingTreeHash = restoredWorkingTreeHash,
                    failureMessage = "선택한 파일을 삭제 상태로 복원하지 못했습니다: ${current.relativePath}",
                    mutation = {
                        if (!workspaceMutator.delete(current.platformFile)) {
                            throw CommitStorageException("파일을 삭제하지 못했습니다: ${current.relativePath}")
                        }
                    },
                    rollback = { restoreOriginalFile(project, current) },
                )
                return@withLock FileRestoreResult(
                    fileId = fileId,
                    side = side,
                    previousPath = current.relativePath,
                    restoredPath = null,
                    changed = true,
                    workingTreeHash = restoredWorkingTreeHash,
                )
            }

            validateRestorePath(target.relativePath)
            val targetKey = FileKey.of(target.relativePath)
            validateRestoreFolder(targetKey)
            val targetContent = loadHistoricalContent(
                store = prepared.store,
                state = target,
                expectedFileId = fileId,
            ).withRequiredProjectTag(normalizeTag(project.name))
            val restoredWorkingTreeHash = prepared.snapshot.tree
                .withFileEntry(
                    fileId = fileId,
                    relativePath = target.relativePath,
                    blobHash = sha256Utf8(targetContent),
                )
                .workingTreeHash()
            val destinationOwner = prepared.existingByPath.values.find { existing ->
                existing.relativePath.equals(target.relativePath, ignoreCase = true)
            }
            if (destinationOwner != null && destinationOwner.fileId != fileId) {
                throw CommitConflictException(
                    "복구 대상 경로를 다른 파일이 사용하고 있습니다: ${target.relativePath}",
                )
            }
            if (
                current != null &&
                current.relativePath != target.relativePath &&
                current.relativePath.equals(target.relativePath, ignoreCase = true)
            ) {
                throw CommitConflictException(
                    "대소문자만 다른 경로로는 파일을 복원할 수 없습니다: ${target.relativePath}",
                )
            }

            if (current?.relativePath == target.relativePath) {
                if (current.content == targetContent) {
                    return@withLock FileRestoreResult(
                        fileId = fileId,
                        side = side,
                        previousPath = current.relativePath,
                        restoredPath = current.relativePath,
                        changed = false,
                        workingTreeHash = restoredWorkingTreeHash,
                    )
                }
                applyRestoreTransaction(
                    project = project,
                    rollbackSnapshot = prepared.snapshot,
                    resultWorkingTreeHash = restoredWorkingTreeHash,
                    failureMessage = "선택한 파일 내용을 복원하지 못했습니다: ${target.relativePath}",
                    mutation = { workspaceMutator.write(current.platformFile, targetContent) },
                    rollback = { restoreOriginalFile(project, current) },
                )
                return@withLock FileRestoreResult(
                    fileId = fileId,
                    side = side,
                    previousPath = current.relativePath,
                    restoredPath = target.relativePath,
                    changed = true,
                    workingTreeHash = restoredWorkingTreeHash,
                )
            }

            var createdDestination: PlatformFile? = null
            applyRestoreTransaction(
                project = project,
                rollbackSnapshot = prepared.snapshot,
                resultWorkingTreeHash = restoredWorkingTreeHash,
                failureMessage = "선택한 파일 전체를 복원하지 못했습니다: ${target.relativePath}",
                mutation = {
                    val parent = resolveOrCreateRestoreFolder(project, targetKey)
                    val occupied = parent.list().find { child ->
                        child.name.equals(targetKey.fileName, ignoreCase = true)
                    }
                    if (occupied != null) {
                        throw CommitConflictException(
                            "복구 대상 경로를 다른 항목이 사용하고 있습니다: ${target.relativePath}",
                        )
                    }
                    // 생성 직후 취소되어도 rollback이 정리할 실제 파일 참조를 잃지 않는다.
                    val destination = withContext(NonCancellable) {
                        workspaceMutator.createFile(
                            parentDirectory = parent,
                            name = targetKey.fileName,
                            mimeType = "text/markdown",
                        )?.also { createdDestination = it }
                            ?: throw CommitStorageException("복구 파일을 만들 수 없습니다: ${target.relativePath}")
                    }
                    currentCoroutineContext().ensureActive()
                    if (destination.name != targetKey.fileName) {
                        throw CommitStorageException(
                            "저장소가 복구 파일명을 변경했습니다: ${target.relativePath} → ${destination.name}",
                        )
                    }

                    workspaceMutator.write(destination, targetContent)
                    currentCoroutineContext().ensureActive()

                    if (current != null && !workspaceMutator.delete(current.platformFile)) {
                        throw CommitStorageException(
                            "복구한 파일을 이전 경로에서 정리하지 못했습니다: ${current.relativePath}",
                        )
                    }
                },
                rollback = {
                    val cleanupError = createdDestination?.let { destination ->
                        cleanupCreatedRestoreFile(destination, target.relativePath)
                    }
                    current?.let { restoreOriginalFile(project, it) }
                    cleanupError?.let { throw it }
                },
            )

            FileRestoreResult(
                fileId = fileId,
                side = side,
                previousPath = current?.relativePath,
                restoredPath = target.relativePath,
                changed = true,
                workingTreeHash = restoredWorkingTreeHash,
            )
        }
    }

    /**
     * 커밋 이력이 없는 Project에 최초 편집 전 기준점을 한 번 만든다.
     * 빈 Project도 빈 tree commit을 기록하며, 이미 HEAD가 있으면 아무것도 쓰지 않고 그 HEAD를 반환한다.
     */
    suspend fun ensureInitialBaseline(project: PlatformFile): ProjectCommit =
        withContext(Dispatchers.IO) {
            commitMutex.withLock {
                val store = FileCommitStore(fileManager, project)
                store.loadHead()?.let { return@withLock it }

                val snapshot = scan(project)
                snapshot.blobs.forEach { (blobHash, content) ->
                    store.writeBlob(blobHash, content)
                }
                val treeHash = store.writeTree(snapshot.tree)
                val commit = createCommitObject(
                    parentId = null,
                    treeHash = treeHash,
                    message = INITIAL_BASELINE_MESSAGE,
                )
                store.writeCommit(commit)
                store.updateHead(commit.id)
                commit
            }
        }

    suspend fun commit(project: PlatformFile, message: String): CommitResult =
        withContext(Dispatchers.IO) {
            commitMutex.withLock {
                val normalizedMessage = message.trim()
                require(normalizedMessage.isNotEmpty()) { "커밋 메시지를 입력해 주세요." }

                val prepared = prepare(project)
                if (!prepared.preview.hasChanges) {
                    throw IllegalStateException("커밋할 변경 사항이 없습니다.")
                }

                val store = FileCommitStore(fileManager, project)
                val newBlobHashes = prepared.preview.changes
                    .mapNotNull(CommitChange::newBlobHash)
                    .toSet()
                newBlobHashes.forEach { blobHash ->
                    val content = prepared.snapshot.blobs[blobHash]
                        ?: throw CommitStorageException("새 blob의 내용을 찾을 수 없습니다: $blobHash")
                    store.writeBlob(blobHash, content)
                }

                val treeHash = store.writeTree(prepared.snapshot.tree)
                val commit = createCommitObject(
                    parentId = prepared.head?.id,
                    treeHash = treeHash,
                    message = normalizedMessage,
                )
                store.writeCommit(commit)
                store.updateHead(commit.id)
                CommitResult(commit, prepared.preview.changes)
            }
        }

    private suspend fun prepare(project: PlatformFile): PreparedCommit {
        val store = FileCommitStore(fileManager, project)
        val head = store.loadHead()
        val previousTree = head?.let { store.loadTree(it.treeHash).trackedContentTree() }
        val workingTree = scanWorkingTree(project)
        val snapshot = workingTree.snapshot
        val changes = enrichChanges(
            store = store,
            changes = CommitPlanner.changes(previousTree, snapshot.tree),
            currentBlobs = snapshot.blobs,
        )
        return PreparedCommit(
            head = head,
            snapshot = snapshot,
            preview = CommitPreview(
                parentCommitId = head?.id,
                changes = changes,
                currentFileIds = workingTree.existingMarkdown.keys,
                workingTreeHash = snapshot.workingTreeHash(),
            ),
            existingMarkdown = workingTree.existingMarkdown,
        )
    }

    private suspend fun prepareFileRestore(
        project: PlatformFile,
        commitId: String,
        fileId: String,
        side: CommitFileSide,
        expectedWorkingTreeHash: String,
    ): PreparedFileRestore {
        val prepared = prepare(project)
        if (prepared.head == null) throw IllegalStateException("복구할 커밋 이력이 없습니다.")
        requireExpectedWorkingTree(prepared.snapshot, expectedWorkingTreeHash)

        val store = FileCommitStore(fileManager, project)
        val change = loadHistoricalFileChange(store, commitId, fileId)
        val targetState = change.state(side)
        return PreparedFileRestore(
            store = store,
            targetState = targetState,
            current = prepared.existingMarkdown[fileId],
            existingByPath = prepared.existingMarkdown.values.associateBy(ExistingMarkdown::relativePath),
            snapshot = prepared.snapshot,
        )
    }

    private fun requireExpectedWorkingTree(
        snapshot: ProjectSnapshot,
        expectedWorkingTreeHash: String,
    ) {
        if (expectedWorkingTreeHash != snapshot.workingTreeHash()) {
            throw RestoreSessionStaleException(
                "확인 화면을 연 뒤 Project가 변경되었습니다. 현재 상태를 다시 확인해 주세요.",
            )
        }
    }

    private suspend fun loadHistoricalFileChange(
        store: FileCommitStore,
        commitId: String,
        fileId: String,
    ): CommitChange {
        val commit = store.loadCommit(commitId)
        val tree = store.loadTree(commit.treeHash).trackedContentTree()
        val parentTree = commit.parentId
            ?.let { store.loadCommit(it) }
            ?.let { store.loadTree(it.treeHash).trackedContentTree() }
        return CommitPlanner.changes(parentTree, tree)
            .find { candidate -> candidate.fileId == fileId }
            ?: throw IllegalArgumentException("선택한 커밋에서 파일 변경을 찾을 수 없습니다.")
    }

    private fun CommitChange.state(side: CommitFileSide): HistoricalFileState? {
        val path = when (side) {
            CommitFileSide.BEFORE -> oldPath
            CommitFileSide.AFTER -> newPath
        }
        val blobHash = when (side) {
            CommitFileSide.BEFORE -> oldBlobHash
            CommitFileSide.AFTER -> newBlobHash
        }
        if (path == null && blobHash == null) return null
        if (path == null || blobHash == null) {
            throw CommitStorageException("커밋의 파일 상태가 올바르지 않습니다: $fileId")
        }
        return HistoricalFileState(path, blobHash)
    }

    private suspend fun loadHistoricalContent(
        store: FileCommitStore,
        state: HistoricalFileState,
        expectedFileId: String,
    ): String {
        val content = store.loadBlob(state.blobHash)
        val storedId = NoteFile.parse(content).id
        if (storedId != expectedFileId) {
            throw CommitStorageException(
                "저장된 파일 ID가 tree와 일치하지 않습니다: ${state.relativePath}",
            )
        }
        return content
    }

    private fun mergeHistoricalContentAtCurrentPlacement(
        historicalContent: String,
        currentContent: String,
        expectedFileId: String,
        requiredProjectTag: String,
        currentManagedTags: Set<String>,
        allManagedTags: Set<String>,
    ): String {
        val current = NoteFile.parse(currentContent)
        val currentId = current.id
        if (currentId != expectedFileId) {
            throw CommitConflictException(
                "현재 파일 ID가 예상과 다릅니다: ${currentId ?: "없음"}",
            )
        }
        val historical = NoteFile.parse(historicalContent)
        return historical
            .withId(currentId)
            .withPlot(current.plot)
            .withTags(
                buildList {
                    add(requiredProjectTag)
                    current.tags.forEach { tag -> if (tag !in this) add(tag) }
                    historical.tags.forEach { tag ->
                        val belongsToCurrentPlacement = tag !in allManagedTags || tag in currentManagedTags
                        if (belongsToCurrentPlacement && tag !in this) add(tag)
                    }
                },
            )
            .inject()
    }

    private suspend fun applyRestoreTransaction(
        project: PlatformFile,
        rollbackSnapshot: ProjectSnapshot,
        resultWorkingTreeHash: String,
        failureMessage: String,
        mutation: suspend () -> Unit,
        rollback: suspend () -> Unit,
    ) {
        val originalHash = rollbackSnapshot.workingTreeHash()
        requireWorkspaceUnchanged(project, originalHash)
        try {
            currentCoroutineContext().ensureActive()
            mutation()
            currentCoroutineContext().ensureActive()
            verifyWorkspaceHash(project, resultWorkingTreeHash, "복원 결과를 검증하지 못했습니다.")
            currentCoroutineContext().ensureActive()
        } catch (restoreError: Throwable) {
            // 취소된 작업도 원상 복구와 검증을 마친 뒤 잠금을 반납해야 한다.
            val rollbackError = withContext(NonCancellable) {
                runCatching {
                    rollback()
                    verifyWorkspaceHash(project, originalHash, "복원 실패 후 원래 상태를 검증하지 못했습니다.")
                }.exceptionOrNull()
            }
            if (rollbackError != null) {
                throw RestoreRollbackFailedException(
                    "$failureMessage 롤백도 실패했습니다: ${rollbackError.message.orEmpty()}",
                    restoreError,
                ).also { it.addSuppressed(rollbackError) }
            }
            if (restoreError is CancellationException) throw restoreError
            throw CommitStorageException(failureMessage, restoreError)
        }
    }

    private suspend fun cleanupCreatedRestoreFile(
        file: PlatformFile,
        relativePath: String,
    ): Throwable? = runCatching {
        if (!workspaceMutator.delete(file)) {
            throw CommitStorageException("임시 복구 파일을 정리하지 못했습니다: $relativePath")
        }
    }.exceptionOrNull()

    private suspend fun restoreOriginalFile(
        project: PlatformFile,
        original: ExistingMarkdown,
    ) {
        val key = FileKey.of(original.relativePath)
        validateRestoreFolder(key)
        val parent = resolveOrCreateRestoreFolder(project, key)
        val occupied = parent.list().find { child ->
            child.name.equals(key.fileName, ignoreCase = true)
        }
        val destination = when {
            occupied == null -> workspaceMutator.createFile(
                parentDirectory = parent,
                name = key.fileName,
                mimeType = "text/markdown",
            ) ?: throw CommitStorageException("원래 파일을 다시 만들 수 없습니다: ${original.relativePath}")
            occupied.isDirectory() -> throw CommitConflictException(
                "원래 파일 경로를 디렉터리가 사용하고 있습니다: ${original.relativePath}",
            )
            NoteFile.parse(occupied.readString()).id != original.fileId ->
                throw CommitConflictException(
                    "원래 파일 경로를 다른 파일이 사용하고 있습니다: ${original.relativePath}",
                )
            else -> occupied
        }
        if (destination.name != key.fileName) {
            throw CommitStorageException(
                "저장소가 원래 파일명을 변경했습니다: ${original.relativePath} → ${destination.name}",
            )
        }
        workspaceMutator.write(destination, original.content)
    }

    private fun validateRestoreFolder(key: FileKey) {
        val relativePath = key.folder.relativePath
        if (
            relativePath.isNotEmpty() &&
            ('/' in relativePath || '\\' in relativePath || !isValidProjectFolderName(relativePath))
        ) {
            throw CommitStorageException("지원하지 않는 복구 디렉터리 경로입니다: $relativePath")
        }
    }

    private suspend fun resolveOrCreateRestoreFolder(
        project: PlatformFile,
        key: FileKey,
    ): PlatformFile {
        if (key.folder.relativePath.isEmpty()) return project
        return fileManager.listFolders(project)
            .find { folder -> folder.key == key.folder }
            ?.platformFile
            ?: workspaceMutator.createFolder(project, key.folder.relativePath)
            ?: throw CommitStorageException(
                "복구 대상 디렉터리를 만들 수 없습니다: ${key.folder.relativePath}",
            )
    }

    private suspend fun enrichChanges(
        store: FileCommitStore,
        changes: List<CommitChange>,
        currentBlobs: Map<String, String>?,
    ): List<CommitChange> = changes.map { change ->
            if (change.kind == CommitChangeKind.RENAMED) return@map change
            val oldContent = change.oldBlobHash?.let { store.loadBlob(it) }
            val newContent = change.newBlobHash?.let { blobHash ->
                currentBlobs?.get(blobHash) ?: store.loadBlob(blobHash)
            }
            val lineCount = LineDiffCounter.count(oldContent, newContent)
            change.copy(
                addedLines = lineCount.added,
                deletedLines = lineCount.deleted,
            )
        }

    private suspend fun scan(project: PlatformFile): ProjectSnapshot = scanWorkingTree(project).snapshot

    private suspend fun scanWorkingTree(project: PlatformFile): WorkingTreeSnapshot {
        val files = mutableListOf<WorkingFile>()
        val existingMarkdown = linkedMapOf<String, ExistingMarkdown>()
        fileManager.listFolders(project).forEach { folder ->
            fileManager.listProjectFiles(folder).forEach { projectFile ->
                // 미리보기·복원 사전검사는 원본을 수정하지 않는다. hash/rollback도 원문 기준이다.
                val content = projectFile.platformFile.readString()
                val note = NoteFile.parse(content)
                val id = note.id
                    ?: throw CommitConflictException(
                        "파일 ID가 없습니다: ${projectFile.key.relativePath}. 프로젝트를 다시 열어 인덱싱하거나 파일을 편집·저장한 뒤 다시 시도해 주세요.",
                    )
                files += WorkingFile(
                    fileId = id,
                    relativePath = projectFile.key.relativePath,
                    content = content,
                )
                if (
                    existingMarkdown.put(
                        id,
                        ExistingMarkdown(
                            fileId = id,
                            relativePath = projectFile.key.relativePath,
                            platformFile = projectFile.platformFile,
                            content = content,
                        ),
                    ) != null
                ) {
                    throw CommitConflictException("동일한 파일 ID가 여러 파일에 사용되고 있습니다: $id")
                }
            }
        }
        return WorkingTreeSnapshot(
            snapshot = CommitPlanner.snapshot(files),
            existingMarkdown = existingMarkdown,
        )
    }

    private suspend fun loadRestoreSnapshot(
        store: FileCommitStore,
        tree: CommitTree,
        requiredProjectTag: String,
    ): ProjectSnapshot = CommitPlanner.snapshot(
        tree.entries.map { entry ->
            val historicalContent = loadHistoricalContent(
                store = store,
                state = HistoricalFileState(entry.relativePath, entry.blobHash),
                expectedFileId = entry.fileId,
            )
            WorkingFile(
                fileId = entry.fileId,
                relativePath = entry.relativePath,
                content = historicalContent.withRequiredProjectTag(requiredProjectTag),
            )
        },
    )

    private fun String.withRequiredProjectTag(requiredProjectTag: String): String {
        val note = NoteFile.parse(this)
        if (normalizeTags(note.tags) == note.tags && requiredProjectTag in note.tags) return this
        val updated = note.withTags(
            listOf(requiredProjectTag) + note.tags.filterNot { it == requiredProjectTag },
        )
        return updated.inject()
    }

    private suspend fun requireWorkspaceUnchanged(
        project: PlatformFile,
        expectedWorkingTreeHash: String,
    ) {
        if (scan(project).workingTreeHash() != expectedWorkingTreeHash) {
            throw RestoreSessionStaleException(
                "복원을 준비하는 동안 Project가 변경되었습니다. 현재 상태를 다시 확인해 주세요.",
            )
        }
    }

    private suspend fun verifyWorkspaceHash(
        project: PlatformFile,
        expectedWorkingTreeHash: String,
        message: String,
    ) {
        if (scan(project).workingTreeHash() != expectedWorkingTreeHash) {
            throw CommitStorageException(message)
        }
    }

    private suspend fun applySnapshot(
        project: PlatformFile,
        tree: CommitTree,
        blobs: Map<String, String>,
    ) {
        val targetMarkdown = tree.trackedContentTree().entries
        targetMarkdown.forEach { entry -> validateRestorePath(entry.relativePath) }

        targetMarkdown
            .map { entry -> FileKey.of(entry.relativePath).folder.relativePath }
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { relativePath ->
                if (
                    '/' in relativePath ||
                    '\\' in relativePath ||
                    !isValidProjectFolderName(relativePath)
                ) {
                    throw CommitStorageException("지원하지 않는 복구 디렉터리 경로입니다: $relativePath")
                }
                workspaceMutator.createFolder(project, relativePath)
                    ?: throw CommitStorageException("복구 디렉터리를 만들 수 없습니다: $relativePath")
            }

        // 적용 중에는 새로 만든 파일이 아직 비어 있거나 write 실패로 잘린 상태일 수 있다.
        // rollback도 호출되는 같은 경로이므로 ID를 다시 파싱하지 않고, 검증된 목표 경로만 보존한다.
        val targetPaths = targetMarkdown.mapTo(mutableSetOf(), CommitTreeEntry::relativePath)
        fileManager.listFolders(project).forEach { folder ->
            fileManager.listProjectFiles(folder).forEach { current ->
                if (current.key.relativePath !in targetPaths && !workspaceMutator.delete(current.platformFile)) {
                    throw CommitStorageException("기존 파일을 삭제하지 못했습니다: ${current.key.relativePath}")
                }
            }
        }

        val folders = fileManager.listFolders(project).associateBy { it.key.relativePath }
        targetMarkdown.forEach { entry ->
            val key = FileKey.of(entry.relativePath)
            val parent = if (key.folder.relativePath.isEmpty()) {
                project
            } else {
                folders[key.folder.relativePath]?.platformFile
                    ?: workspaceMutator.createFolder(project, key.folder.relativePath)
                    ?: throw CommitStorageException(
                        "복구 대상 디렉터리를 만들 수 없습니다: ${key.folder.relativePath}",
                    )
            }
            val destination = parent.list().find { child ->
                !child.isDirectory() && child.name == key.fileName
            } ?: workspaceMutator.createFile(
                parentDirectory = parent,
                name = key.fileName,
                mimeType = "text/markdown",
            ) ?: throw CommitStorageException("복구 파일을 만들 수 없습니다: ${entry.relativePath}")
            if (destination.name != key.fileName) {
                throw CommitStorageException(
                    "저장소가 복구 파일명을 변경했습니다: ${entry.relativePath} → ${destination.name}",
                )
            }
            val content = blobs[entry.blobHash]
                ?: throw CommitStorageException("복구 파일 내용을 찾을 수 없습니다: ${entry.relativePath}")
            workspaceMutator.write(destination, content)
        }
    }

    private fun ProjectSnapshot.workingTreeHash(): String = tree.workingTreeHash()

    private fun CommitTree.workingTreeHash(): String =
        sha256Utf8(CommitObjectCodec.encodeTree(trackedContentTree()))

    private fun CommitTree.withFileEntry(
        fileId: String,
        relativePath: String?,
        blobHash: String?,
    ): CommitTree {
        require((relativePath == null) == (blobHash == null)) {
            "파일 경로와 blob hash는 함께 있어야 합니다."
        }
        val replacement = if (relativePath == null || blobHash == null) {
            emptyList()
        } else {
            listOf(CommitTreeEntry(fileId, relativePath, blobHash))
        }
        return CommitTree(
            entries = (trackedContentTree().entries.filterNot { it.fileId == fileId } + replacement)
                .sortedWith(compareBy(CommitTreeEntry::fileId, CommitTreeEntry::relativePath)),
        )
    }

    private fun createCommitObject(
        parentId: String?,
        treeHash: String,
        message: String,
    ): ProjectCommit {
        val createdAt = Clock.System.now().toEpochMilliseconds()
        val id = CommitObjectCodec.calculateCommitId(
            parentId = parentId,
            treeHash = treeHash,
            createdAtEpochMillis = createdAt,
            message = message,
        )
        return ProjectCommit(
            id = id,
            parentId = parentId,
            treeHash = treeHash,
            createdAtEpochMillis = createdAt,
            message = message,
        )
    }

    /** 1차 구현에서 저장된 설정 entry도 읽을 수 있지만 이후 diff·restore 대상에서는 제외한다. */
    private fun CommitTree.trackedContentTree(): CommitTree = copy(
        entries = entries.filterNot { entry ->
            entry.fileId == PROJECT_CONFIG_ID || entry.relativePath == PROJECT_CONFIG_PATH
        },
    )

    private fun validateRestorePath(relativePath: String) {
        val key = runCatching { FileKey.of(relativePath) }.getOrElse {
            throw CommitStorageException("잘못된 복구 파일 경로입니다: $relativePath", it)
        }
        if (!key.fileName.endsWith(".md", ignoreCase = true) || '/' in key.folder.relativePath) {
            throw CommitStorageException("지원하지 않는 복구 파일 경로입니다: $relativePath")
        }
    }

    private data class ExistingMarkdown(
        val fileId: String,
        val relativePath: String,
        val platformFile: PlatformFile,
        val content: String,
    )

    private data class WorkingTreeSnapshot(
        val snapshot: ProjectSnapshot,
        val existingMarkdown: Map<String, ExistingMarkdown>,
    )

    private data class HistoricalFileState(
        val relativePath: String,
        val blobHash: String,
    )

    private data class PreparedFileRestore(
        val store: FileCommitStore,
        val targetState: HistoricalFileState?,
        val current: ExistingMarkdown?,
        val existingByPath: Map<String, ExistingMarkdown>,
        val snapshot: ProjectSnapshot,
    )

    private data class PreparedCommit(
        val head: ProjectCommit?,
        val snapshot: ProjectSnapshot,
        val preview: CommitPreview,
        val existingMarkdown: Map<String, ExistingMarkdown>,
    )

    private companion object {
        const val INITIAL_BASELINE_MESSAGE = "초기 기준점"
        const val PROJECT_CONFIG_ID = "machum:project-config"
        const val PROJECT_CONFIG_PATH = ".machum.json"
    }
}

/** 복원 실패를 결정적으로 검증할 수 있도록 mutation 경계만 좁게 분리한다. */
internal interface CommitWorkspaceMutator {
    suspend fun createFolder(parentDirectory: PlatformFile, name: String): PlatformFile?
    suspend fun createFile(
        parentDirectory: PlatformFile,
        name: String,
        mimeType: String,
    ): PlatformFile?
    suspend fun write(file: PlatformFile, content: String)
    suspend fun delete(file: PlatformFile): Boolean
}

private class FileManagerCommitWorkspaceMutator(
    private val fileManager: FileManager,
) : CommitWorkspaceMutator {
    override suspend fun createFolder(parentDirectory: PlatformFile, name: String): PlatformFile? =
        fileManager.createFolder(parentDirectory, name)

    override suspend fun createFile(
        parentDirectory: PlatformFile,
        name: String,
        mimeType: String,
    ): PlatformFile? = fileManager.createCommitStorageFile(parentDirectory, name, mimeType)

    override suspend fun write(file: PlatformFile, content: String) {
        fileManager.write(file, content)
    }

    override suspend fun delete(file: PlatformFile): Boolean = fileManager.delete(file)
}
