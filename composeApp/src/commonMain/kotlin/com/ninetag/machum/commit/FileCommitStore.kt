package com.ninetag.machum.commit

import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.createFolder
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString

internal class FileCommitStore(
    private val fileManager: FileManager,
    private val project: PlatformFile,
) {
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
        return CommitObjectCodec.decode(
            ProjectCommit.serializer(),
            commitFile.readString(),
            "커밋 $commitId",
        )
    }

    suspend fun loadTree(treeHash: String): CommitTree {
        val root = findDirectory(project, STORE_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소를 찾을 수 없습니다.")
        val trees = findDirectory(root, TREES_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소에 trees 디렉터리가 없습니다.")
        val treeFile = findFile(trees, "$treeHash.json")
            ?: throw CommitStorageException("커밋 tree를 찾을 수 없습니다: $treeHash")
        return CommitObjectCodec.decode(
            CommitTree.serializer(),
            treeFile.readString(),
            "tree $treeHash",
        )
    }

    suspend fun loadBlob(blobHash: String): String {
        val root = findDirectory(project, STORE_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소를 찾을 수 없습니다.")
        val blobs = findDirectory(root, BLOBS_DIRECTORY)
            ?: throw CommitStorageException("커밋 저장소에 blobs 디렉터리가 없습니다.")
        val blobFile = findFile(blobs, "$blobHash.blob")
            ?: throw CommitStorageException("파일 내용을 찾을 수 없습니다: $blobHash")
        return blobFile.readString().also { content ->
            if (sha256Utf8(content) != blobHash) {
                throw CommitStorageException("저장된 파일 내용의 해시가 일치하지 않습니다: $blobHash")
            }
        }
    }

    suspend fun writeBlob(blobHash: String, content: String) {
        require(sha256Utf8(content) == blobHash) { "blobHash does not match content" }
        val directories = ensureDirectories()
        writeIfAbsent(
            parent = directories.blobs,
            name = "$blobHash.blob",
            content = content,
            mimeType = "text/plain",
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
        parent.list().find { child -> child.isDirectory() && child.name == name }

    private fun findFile(parent: PlatformFile, name: String): PlatformFile? =
        parent.list().find { child -> !child.isDirectory() && child.name == name }

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
    }
}
