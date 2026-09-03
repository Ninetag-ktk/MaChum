package com.ninetag.machum.commit

import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.createFolder
import com.ninetag.machum.external.isValidProjectFolderName
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class ProjectCommitService(
    private val fileManager: FileManager,
) {
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

    suspend fun restore(project: PlatformFile, commitId: String): RestoreResult =
        withContext(Dispatchers.IO) {
            commitMutex.withLock {
                val prepared = prepare(project)
                if (prepared.head == null) {
                    throw IllegalStateException("복구할 커밋 이력이 없습니다.")
                }
                if (prepared.preview.hasChanges) {
                    throw UncommittedChangesException(
                        "현재 변경 사항을 먼저 커밋한 뒤 복구해 주세요.",
                    )
                }

                val store = FileCommitStore(fileManager, project)
                val targetCommit = store.loadCommit(commitId)
                if (targetCommit.id == prepared.head.id) {
                    return@withLock RestoreResult(targetCommit, changedFiles = 0)
                }
                val targetTree = store.loadTree(targetCommit.treeHash).trackedContentTree()
                val targetBlobs = loadSnapshotBlobs(store, targetTree)
                val rollbackTree = prepared.snapshot.tree
                val rollbackBlobs = prepared.snapshot.blobs
                val changedFiles = CommitPlanner.changes(rollbackTree, targetTree).size

                runCatching {
                    applySnapshot(project, targetTree, targetBlobs)
                }.onFailure { restoreError ->
                    val rollbackError = runCatching {
                        applySnapshot(project, rollbackTree, rollbackBlobs)
                    }.exceptionOrNull()
                    val suffix = rollbackError?.message?.let { " 롤백도 실패했습니다: $it" }.orEmpty()
                    throw CommitStorageException(
                        "선택한 커밋을 복구하지 못했습니다.${restoreError.message?.let { " $it" }.orEmpty()}$suffix",
                        restoreError,
                    )
                }
                RestoreResult(targetCommit, changedFiles)
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
                val createdAt = Clock.System.now().toEpochMilliseconds()
                val parentId = prepared.head?.id
                val id = sha256Utf8(
                    buildString {
                        append(parentId.orEmpty()).append('\n')
                        append(treeHash).append('\n')
                        append(createdAt).append('\n')
                        append(normalizedMessage.length).append(':').append(normalizedMessage)
                    },
                )
                val commit = ProjectCommit(
                    id = id,
                    parentId = parentId,
                    treeHash = treeHash,
                    createdAtEpochMillis = createdAt,
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
        val snapshot = scan(project)
        val changes = enrichChanges(
            store = store,
            changes = CommitPlanner.changes(previousTree, snapshot.tree),
            currentBlobs = snapshot.blobs,
        )
        return PreparedCommit(
            head = head,
            snapshot = snapshot,
            preview = CommitPreview(head?.id, changes),
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

    private suspend fun scan(project: PlatformFile): ProjectSnapshot {
        val files = mutableListOf<WorkingFile>()
        fileManager.listFolders(project).forEach { folder ->
            fileManager.listProjectFiles(folder).forEach { projectFile ->
                val note = fileManager.readMarkdown(projectFile.platformFile)
                val id = note.id
                    ?: throw CommitConflictException(
                        "파일 ID를 만들 수 없습니다: ${projectFile.key.relativePath}",
                    )
                files += WorkingFile(
                    fileId = id,
                    relativePath = projectFile.key.relativePath,
                    content = note.inject(),
                )
            }
        }
        return CommitPlanner.snapshot(files)
    }

    private suspend fun loadSnapshotBlobs(
        store: FileCommitStore,
        tree: CommitTree,
    ): Map<String, String> = tree.entries
        .map(CommitTreeEntry::blobHash)
        .distinct()
        .associateWith { blobHash -> store.loadBlob(blobHash) }

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
                fileManager.createFolder(project, relativePath)
                    ?: throw CommitStorageException("복구 디렉터리를 만들 수 없습니다: $relativePath")
            }

        val existing = scanExistingMarkdown(project)
        val targetById = targetMarkdown.associateBy(CommitTreeEntry::fileId)
        existing.values.forEach { current ->
            val target = targetById[current.fileId]
            if (target == null || target.relativePath != current.relativePath) {
                fileManager.delete(current.platformFile)
            }
        }

        val folders = fileManager.listFolders(project).associateBy { it.key.relativePath }
        targetMarkdown.forEach { entry ->
            val key = FileKey.of(entry.relativePath)
            val parent = if (key.folder.relativePath.isEmpty()) {
                project
            } else {
                folders[key.folder.relativePath]?.platformFile
                    ?: fileManager.createFolder(project, key.folder.relativePath)
                    ?: throw CommitStorageException(
                        "복구 대상 디렉터리를 만들 수 없습니다: ${key.folder.relativePath}",
                    )
            }
            val destination = parent.list().find { child ->
                !child.isDirectory() && child.name == key.fileName
            } ?: fileManager.createCommitStorageFile(
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
            fileManager.write(destination, content)
        }
    }

    /** 1차 구현에서 저장된 설정 entry도 읽을 수 있지만 이후 diff·restore 대상에서는 제외한다. */
    private fun CommitTree.trackedContentTree(): CommitTree = copy(
        entries = entries.filterNot { entry ->
            entry.fileId == PROJECT_CONFIG_ID || entry.relativePath == PROJECT_CONFIG_PATH
        },
    )

    private suspend fun scanExistingMarkdown(project: PlatformFile): Map<String, ExistingMarkdown> {
        val result = linkedMapOf<String, ExistingMarkdown>()
        fileManager.listFolders(project).forEach { folder ->
            fileManager.listProjectFiles(folder).forEach { projectFile ->
                val note = fileManager.readMarkdown(projectFile.platformFile)
                val id = note.id ?: throw CommitConflictException(
                    "파일 ID를 읽을 수 없습니다: ${projectFile.key.relativePath}",
                )
                if (result.put(id, ExistingMarkdown(id, projectFile.key.relativePath, projectFile.platformFile)) != null) {
                    throw CommitConflictException("동일한 파일 ID가 여러 파일에 사용되고 있습니다: $id")
                }
            }
        }
        return result
    }

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
    )

    private data class PreparedCommit(
        val head: ProjectCommit?,
        val snapshot: ProjectSnapshot,
        val preview: CommitPreview,
    )

    private companion object {
        const val PROJECT_CONFIG_ID = "machum:project-config"
        const val PROJECT_CONFIG_PATH = ".machum.json"
    }
}
