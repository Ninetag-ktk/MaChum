package com.ninetag.machum.commit

import kotlinx.serialization.Serializable

@Serializable
data class CommitTreeEntry(
    val fileId: String,
    val relativePath: String,
    val blobHash: String,
)

@Serializable
data class CommitTree(
    val entries: List<CommitTreeEntry>,
)

@Serializable
data class ProjectCommit(
    val id: String,
    val parentId: String? = null,
    val treeHash: String,
    val createdAtEpochMillis: Long,
    val message: String,
)

@Serializable
internal data class CommitHead(
    val commitId: String,
)

enum class CommitChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    RENAMED_AND_MODIFIED,
}

data class CommitChange(
    val fileId: String,
    val kind: CommitChangeKind,
    val oldPath: String? = null,
    val newPath: String? = null,
    val oldBlobHash: String? = null,
    val newBlobHash: String? = null,
    val addedLines: Int = 0,
    val deletedLines: Int = 0,
) {
    val displayPath: String
        get() = newPath ?: oldPath.orEmpty()
}

data class CommitPreview(
    val parentCommitId: String?,
    val changes: List<CommitChange>,
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
}

data class CommitResult(
    val commit: ProjectCommit,
    val changes: List<CommitChange>,
)

data class CommitHistoryEntry(
    val commit: ProjectCommit,
    val changes: List<CommitChange>,
)

enum class LineDiffKind {
    CONTEXT,
    ADDED,
    DELETED,
    OMITTED,
}

data class LineDiffLine(
    val kind: LineDiffKind,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

data class FileLineDiff(
    val change: CommitChange,
    val lines: List<LineDiffLine>,
    val isTruncated: Boolean = false,
    val isApproximate: Boolean = false,
)

data class RestoreResult(
    val targetCommit: ProjectCommit,
    val changedFiles: Int,
)

internal data class WorkingFile(
    val fileId: String,
    val relativePath: String,
    val content: String,
)

internal data class ProjectSnapshot(
    val tree: CommitTree,
    val blobs: Map<String, String>,
)

class CommitConflictException(message: String) : IllegalStateException(message)

class CommitStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class UncommittedChangesException(message: String) : IllegalStateException(message)
