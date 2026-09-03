package com.ninetag.machum.backup

import com.ninetag.machum.commit.CommitObjectCodec
import com.ninetag.machum.commit.CommitChangeKind
import com.ninetag.machum.commit.CommitPlanner
import com.ninetag.machum.commit.CommitStorageException
import com.ninetag.machum.commit.CommitTree
import com.ninetag.machum.commit.FileCommitStore
import com.ninetag.machum.commit.ProjectCommit
import com.ninetag.machum.commit.sha256Utf8
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommitBackupPlanSource(
    private val fileManager: FileManager,
) {
    suspend fun create(
        project: PlatformFile,
        targetCommitId: String,
        baseCommitId: String?,
        repositoryIdentity: ProjectRepositoryIdentity,
    ): CommitBackupPlan = withContext(Dispatchers.IO) {
        require(targetCommitId.isNotBlank()) { "targetCommitId must not be blank" }

        val store = FileCommitStore(fileManager, project)
        val targetCommit = store.loadCommit(targetCommitId)
        val pendingCommits = collectPendingCommits(store, targetCommit, baseCommitId)
        val pendingTrees = pendingCommits.associateWith { commit ->
            store.loadTree(commit.treeHash)
        }

        val blobHashes = collectNewBlobHashes(store, pendingCommits, pendingTrees, baseCommitId)
        val blobs = blobHashes.map { blobHash ->
            val content = store.loadBlob(blobHash)
            ImmutableBackupObject(
                kind = ImmutableBackupObjectKind.BLOB,
                relativePath = "history/blobs/$blobHash.blob",
                content = content,
                contentHash = blobHash,
            )
        }
        val trees = pendingTrees.entries
            .distinctBy { (commit, _) -> commit.treeHash }
            .map { (commit, tree) ->
                val content = CommitObjectCodec.encodeTree(tree)
                val contentHash = sha256Utf8(content)
                if (contentHash != commit.treeHash) {
                    throw CommitStorageException(
                        "백업할 tree의 해시가 일치하지 않습니다: ${commit.treeHash}",
                    )
                }
                ImmutableBackupObject(
                    kind = ImmutableBackupObjectKind.TREE,
                    relativePath = "history/trees/${commit.treeHash}.json",
                    content = content,
                    contentHash = contentHash,
                )
            }
            .distinctBy(ImmutableBackupObject::relativePath)
            .sortedBy(ImmutableBackupObject::relativePath)
        val commits = pendingCommits.map { commit ->
            val content = CommitObjectCodec.encodeCommit(commit)
            ImmutableBackupObject(
                kind = ImmutableBackupObjectKind.COMMIT,
                relativePath = "history/commits/${commit.id}.json",
                content = content,
                contentHash = sha256Utf8(content),
            )
        }

        val targetTree = store.loadTree(targetCommit.treeHash).trackedContentTree()
        val workspace = targetTree.entries
            .map { entry ->
                BackupWorkspaceEntry(
                    fileId = entry.fileId,
                    relativePath = FileKey.of(entry.relativePath).relativePath,
                    blobHash = entry.blobHash,
                )
            }
            .sortedWith(compareBy(BackupWorkspaceEntry::relativePath, BackupWorkspaceEntry::fileId))
        validateWorkspace(workspace)

        val headContent = CommitObjectCodec.encodeHead(targetCommit.id)
        CommitBackupPlan(
            projectId = repositoryIdentity.projectId,
            baseCommitId = baseCommitId,
            targetCommitId = targetCommit.id,
            immutableObjects = blobs + trees + commits,
            metadata = projectMetadata(project, repositoryIdentity),
            workspace = workspace,
            head = MutableBackupObject(
                relativePath = "history/HEAD.json",
                content = headContent,
                contentHash = sha256Utf8(headContent),
            ),
        )
    }

    private suspend fun projectMetadata(
        project: PlatformFile,
        repositoryIdentity: ProjectRepositoryIdentity,
    ): List<MutableBackupObject> {
        val repositoryContent = ProjectRepositoryIdentityStore(fileManager).encode(repositoryIdentity)
        val result = mutableListOf(
            MutableBackupObject(
                relativePath = "metadata/repository.json",
                content = repositoryContent,
                contentHash = sha256Utf8(repositoryContent),
            ),
        )
        val config = project.list().find { child ->
            !child.isDirectory() && child.name == PROJECT_CONFIG_PATH
        }
        if (config != null) {
            val content = config.readString()
            result += MutableBackupObject(
                relativePath = "metadata/project-config.json",
                content = content,
                contentHash = sha256Utf8(content),
            )
        }
        return result
    }

    private suspend fun collectPendingCommits(
        store: FileCommitStore,
        targetCommit: ProjectCommit,
        baseCommitId: String?,
    ): List<ProjectCommit> {
        val newestFirst = mutableListOf<ProjectCommit>()
        val visited = mutableSetOf<String>()
        var current = targetCommit

        while (current.id != baseCommitId) {
            if (!visited.add(current.id)) {
                throw CommitStorageException("백업할 커밋 parent 연결에 순환이 있습니다: ${current.id}")
            }
            newestFirst += current
            val parentId = current.parentId
            if (parentId == null) {
                if (baseCommitId != null) {
                    throw BackupHistoryMismatchException(
                        "원격 HEAD가 현재 프로젝트 커밋 이력에 없습니다: $baseCommitId",
                    )
                }
                break
            }
            current = store.loadCommit(parentId)
        }
        return newestFirst.asReversed()
    }

    private suspend fun collectNewBlobHashes(
        store: FileCommitStore,
        pendingCommits: List<ProjectCommit>,
        pendingTrees: Map<ProjectCommit, CommitTree>,
        baseCommitId: String?,
    ): List<String> {
        var previousTree = baseCommitId
            ?.let { store.loadCommit(it) }
            ?.let { store.loadTree(it.treeHash) }
        val blobHashes = linkedSetOf<String>()
        pendingCommits.forEach { commit ->
            val currentTree = pendingTrees.getValue(commit)
            CommitPlanner.changes(previousTree, currentTree)
                .filterNot { change -> change.kind == CommitChangeKind.RENAMED }
                .mapNotNullTo(blobHashes) { change -> change.newBlobHash }
            previousTree = currentTree
        }
        return blobHashes.sorted()
    }

    private fun CommitTree.trackedContentTree(): CommitTree = copy(
        entries = entries.filterNot { entry ->
            entry.fileId == LEGACY_PROJECT_CONFIG_ID ||
                entry.relativePath == PROJECT_CONFIG_PATH
        },
    )

    private fun validateWorkspace(entries: List<BackupWorkspaceEntry>) {
        val duplicateIds = entries.groupBy(BackupWorkspaceEntry::fileId).filterValues { it.size > 1 }
        if (duplicateIds.isNotEmpty()) {
            throw CommitStorageException(
                "백업 작업본에 중복 파일 ID가 있습니다: ${duplicateIds.keys.sorted().joinToString()}",
            )
        }
        val duplicatePaths = entries
            .groupBy(BackupWorkspaceEntry::relativePath)
            .filterValues { it.size > 1 }
        if (duplicatePaths.isNotEmpty()) {
            throw CommitStorageException(
                "백업 작업본에 중복 경로가 있습니다: ${duplicatePaths.keys.sorted().joinToString()}",
            )
        }
    }

    private companion object {
        const val LEGACY_PROJECT_CONFIG_ID = "machum:project-config"
        const val PROJECT_CONFIG_PATH = ".machum.json"
    }
}
