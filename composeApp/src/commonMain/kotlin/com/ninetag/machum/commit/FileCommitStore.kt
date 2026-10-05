package com.ninetag.machum.commit

import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.createFolder
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class FileCommitStore(
    private val fileManager: FileManager,
    private val project: PlatformFile,
    private val cacheDirectoryListings: Boolean = false,
    private val listDirectory: (PlatformFile) -> List<PlatformFile> = { it.list() },
) {
    private val directoryListings = mutableMapOf<String, List<PlatformFile>>()

    suspend fun loadHead(): ProjectCommit? {
        val root = findDirectory(project, STORE_DIRECTORY) ?: return null
        val headFile = findFile(root, HEAD_FILE) ?: return null
        val head = CommitObjectCodec.decode(CommitHead.serializer(), headFile.readString(), "HEAD")
        return loadCommit(head.commitId)
    }

    suspend fun loadCommit(commitId: String): ProjectCommit {
        val root = findDirectory(project, STORE_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소를 찾을 수 없습니다.")
        val commits = findDirectory(root, COMMITS_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소에 commits 디렉터리가 없습니다.")
        val commitFile = findFile(commits, "$commitId.json")
            ?: throw CommitStorageException("커밋을 찾을 수 없습니다: $commitId")
        val commit = CommitObjectCodec.decode(
            ProjectCommit.serializer(),
            commitFile.readString(),
            "커밋 $commitId",
        )
        if (commit.id != commitId) {
            throw CommitStorageException("저장된 커밋 ID가 요청한 ID와 일치하지 않습니다: $commitId")
        }
        if (CommitObjectCodec.calculateCommitId(commit) != commit.id) {
            throw CommitStorageException("저장된 커밋 내용과 ID가 일치하지 않습니다: $commitId")
        }
        return commit
    }

    suspend fun loadTree(treeHash: String): CommitTree {
        val root = findDirectory(project, STORE_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소를 찾을 수 없습니다.")
        val trees = findDirectory(root, TREES_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소에 trees 디렉터리가 없습니다.")
        val treeFile = findFile(trees, "$treeHash.json")
            ?: throw CommitStorageException("커밋 tree를 찾을 수 없습니다: $treeHash")
        val tree = CommitObjectCodec.decode(
            CommitTree.serializer(),
            treeFile.readString(),
            "tree $treeHash",
        )
        if (sha256Utf8(CommitObjectCodec.encodeTree(tree)) != treeHash) {
            throw CommitStorageException("저장된 tree 내용의 해시가 일치하지 않습니다: $treeHash")
        }
        return tree
    }

    suspend fun loadBlob(blobHash: String): String {
        val root = findDirectory(project, STORE_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소를 찾을 수 없습니다.")
        val blobs = findDirectory(root, BLOBS_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소에 blobs 디렉터리가 없습니다.")
        val blobFile = findFile(blobs, "$blobHash.blob")
            ?: findFile(blobs, "$blobHash.blob.txt")
            ?: throw CommitStorageException("파일 내용을 찾을 수 없습니다: $blobHash")
        return blobFile.readString().also { content ->
            if (sha256Utf8(content) != blobHash) {
                throw CommitStorageException("저장된 파일 내용의 해시가 일치하지 않습니다: $blobHash")
            }
        }
    }

    suspend fun loadMessageOverrides(): Map<String, String> {
        val root = findDirectory(project, STORE_DIRECTORY) ?: return emptyMap()
        val file = findFile(root, MESSAGE_OVERRIDES_FILE) ?: return emptyMap()
        return CommitObjectCodec.decodeMessageOverrides(file.readString())
    }

    suspend fun encodeMessageOverridesForBackup(): String? {
        val root = findDirectory(project, STORE_DIRECTORY) ?: return null
        val file = findFile(root, MESSAGE_OVERRIDES_FILE) ?: return null
        return CommitObjectCodec.encodeMessageOverrides(CommitObjectCodec.decodeMessageOverrides(file.readString()))
    }

    suspend fun saveMessageOverrides(
        expected: Map<String, String>,
        updated: Map<String, String>,
        write: suspend (PlatformFile, String) -> Unit,
        delete: suspend (PlatformFile) -> Boolean,
    ) = withContext(NonCancellable) {
        val root = findDirectory(project, STORE_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소를 찾을 수 없습니다.")
        val file = findFile(root, MESSAGE_OVERRIDES_FILE)
        val originalBytes = file?.readBytes()
        val original = originalBytes?.decodeToString(throwOnInvalidSequence = true)
        val stored = original?.let(CommitObjectCodec::decodeMessageOverrides).orEmpty()
        if (stored != expected) throw CommitConflictException("커밋 표시 메시지가 외부에서 변경되었습니다.")
        val content = CommitObjectCodec.encodeMessageOverrides(updated)
        if (content == original) return@withContext

        val target = file ?: (fileManager.createCommitStorageFile(
            parentDirectory = root,
            name = MESSAGE_OVERRIDES_FILE,
            mimeType = "application/json",
        ) ?: throw CommitStorageException("커밋 메시지 파일을 만들 수 없습니다."))
        // ponytail: SAF has no atomic compare-and-swap; add platform transactions if cross-process writers need stronger guarantees.
        if (!target.readBytes().contentEquals(originalBytes ?: byteArrayOf())) {
            throw CommitConflictException("커밋 표시 메시지가 외부에서 변경되었습니다.")
        }
        try {
            write(target, content)
            check(target.readBytes().contentEquals(content.encodeToByteArray())) { "커밋 메시지 기록 결과를 확인하지 못했습니다." }
        } catch (failure: Exception) {
            val observed = runCatching { target.readBytes() }.getOrNull()
            if (observed == null) {
                throw CommitStorageException("커밋 메시지 기록 실패 후 현재 값을 확인하지 못해 복구를 보류했습니다.", failure)
            }
            val intendedBytes = content.encodeToByteArray()
            val isIntendedPrefix = observed.size <= intendedBytes.size && observed.indices.all { observed[it] == intendedBytes[it] }
            if (originalBytes != null && observed.contentEquals(originalBytes)) throw failure
            if (!isIntendedPrefix) {
                throw CommitStorageException("커밋 메시지 기록 실패 후 외부 변경이 감지되어 덮어쓰지 않았습니다.", failure)
            }
            try {
                check(target.readBytes().contentEquals(observed)) { "복구 전에 커밋 메시지가 변경되었습니다." }
                if (originalBytes == null) {
                    check(delete(target) && findFile(root, MESSAGE_OVERRIDES_FILE) == null) {
                        "새 커밋 메시지 파일을 제거하지 못했습니다."
                    }
                } else {
                    write(target, original!!)
                    check(target.readBytes().contentEquals(originalBytes)) { "커밋 메시지 원문을 복구하지 못했습니다." }
                }
            } catch (rollbackFailure: Exception) {
                rollbackFailure.addSuppressed(failure)
                throw CommitStorageException("커밋 메시지 저장에 실패했고 이전 값을 복구하지 못했습니다.", rollbackFailure)
            }
            throw failure
        }
    }

    suspend fun writeBlob(blobHash: String, content: String) {
        require(sha256Utf8(content) == blobHash) { "blobHash does not match content" }
        val directories = ensureDirectories()
        val existing = findFile(directories.blobs, "$blobHash.blob")
            ?: findFile(directories.blobs, "$blobHash.blob.txt")
        if (existing != null) {
            if (existing.readString() != content) {
                throw CommitStorageException("기존 blob $blobHash 내용이 예상과 다릅니다.")
            }
            return
        }
        writeIfAbsent(
            parent = directories.blobs,
            name = "$blobHash.blob",
            content = content,
            mimeType = "application/octet-stream",
            description = "blob $blobHash",
        )
    }

    suspend fun writeTree(tree: CommitTree): String {
        val content = CommitObjectCodec.encodeTree(tree)
        val treeHash = sha256Utf8(content)
        val directories = ensureDirectories()
        writeIfAbsent(
            parent = directories.trees,
            name = "$treeHash.json",
            content = content,
            mimeType = "application/json",
            description = "tree $treeHash",
        )
        return treeHash
    }

    suspend fun writeCommit(commit: ProjectCommit) {
        require(CommitObjectCodec.calculateCommitId(commit) == commit.id) {
            "commit id does not match commit content"
        }
        val content = CommitObjectCodec.encodeCommit(commit)
        val directories = ensureDirectories()
        writeIfAbsent(
            parent = directories.commits,
            name = "${commit.id}.json",
            content = content,
            mimeType = "application/json",
            description = "commit ${commit.id}",
        )
    }

    suspend fun updateHead(commitId: String) {
        val directories = ensureDirectories()
        val content = CommitObjectCodec.encodeHead(commitId)
        val head = findFile(directories.root, HEAD_FILE)
            ?: fileManager.createCommitStorageFile(
                parentDirectory = directories.root,
                name = HEAD_FILE,
                mimeType = "application/json",
            )
            ?: throw CommitStorageException("커밋 HEAD 파일을 만들 수 없습니다.")
        head.writeString(content)
    }

    private suspend fun ensureDirectories(): StoreDirectories {
        val root = fileManager.createFolder(project, STORE_DIRECTORY)
            ?: throw CommitStorageException("프로젝트에 $STORE_DIRECTORY 저장소를 만들 수 없습니다.")
        val blobs = fileManager.createFolder(root, BLOBS_DIRECTORY)
            ?: throw CommitStorageException("커밋 blob 디렉터리를 만들 수 없습니다.")
        val trees = fileManager.createFolder(root, TREES_DIRECTORY)
            ?: throw CommitStorageException("커밋 tree 디렉터리를 만들 수 없습니다.")
        val commits = fileManager.createFolder(root, COMMITS_DIRECTORY)
            ?: throw CommitStorageException("커밋 객체 디렉터리를 만들 수 없습니다.")
        return StoreDirectories(root, blobs, trees, commits)
    }

    private suspend fun writeIfAbsent(
        parent: PlatformFile,
        name: String,
        content: String,
        mimeType: String,
        description: String,
    ) {
        val existing = findFile(parent, name)
        if (existing != null) {
            if (existing.readString() != content) {
                throw CommitStorageException("기존 $description 내용이 예상과 다릅니다.")
            }
            return
        }
        val created = fileManager.createCommitStorageFile(parent, name, mimeType)
            ?: throw CommitStorageException("$description 파일을 만들 수 없습니다.")
        created.writeString(content)
    }

    private fun findDirectory(parent: PlatformFile, name: String): PlatformFile? =
        directoryListing(parent).find { child -> child.isDirectory() && child.name == name }

    private fun findFile(parent: PlatformFile, name: String): PlatformFile? =
        directoryListing(parent).find { child -> !child.isDirectory() && child.name == name }

    private fun directoryListing(parent: PlatformFile): List<PlatformFile> =
        if (cacheDirectoryListings) directoryListings.getOrPut(parent.path) { listDirectory(parent) }
        else listDirectory(parent)

    private data class StoreDirectories(
        val root: PlatformFile,
        val blobs: PlatformFile,
        val trees: PlatformFile,
        val commits: PlatformFile,
    )

    private companion object {
        const val STORE_DIRECTORY = ".machum"
        const val BLOBS_DIRECTORY = "blobs"
        const val TREES_DIRECTORY = "trees"
        const val COMMITS_DIRECTORY = "commits"
        const val HEAD_FILE = "HEAD.json"
        const val MESSAGE_OVERRIDES_FILE = "commit-messages.json"
    }
}
