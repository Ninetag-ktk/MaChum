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
    /** 현재 working tree에 실제로 존재하는 추적 Markdown의 정체성. 복원 가능 여부 표시에만 사용한다. */
    val currentFileIds: Set<String> = emptySet(),
    /** 미리보기 시점 working tree의 canonical tree hash. 확인창 이후 외부 변경을 감지한다. */
    val workingTreeHash: String = "",
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
    /** 복원 직후 working tree의 canonical tree hash. 연속 복원 안전성 검증에 사용한다. */
    val workingTreeHash: String,
)

/** 커밋 변경의 어느 쪽 파일 상태를 복원할지 나타내는 런타임 값. 저장 객체에는 기록하지 않는다. */
enum class CommitFileSide {
    BEFORE,
    AFTER,
}

data class FileRestoreResult(
    val fileId: String,
    val side: CommitFileSide,
    val previousPath: String?,
    val restoredPath: String?,
    val changed: Boolean,
    /** 복원 직후 Project 전체 working tree의 canonical tree hash. */
    val workingTreeHash: String,
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

open class CommitStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** 복원 실패 뒤 원래 working tree까지 완전히 검증하지 못해 복원 세션을 폐기해야 한다. */
class RestoreRollbackFailedException(message: String, cause: Throwable? = null) :
    CommitStorageException(message, cause)

open class UncommittedChangesException(message: String) : IllegalStateException(message)

/** 직전 preview/복원 뒤 working tree가 바뀌어 안전 토큰을 더 이상 사용할 수 없다. */
class RestoreSessionStaleException(message: String) : UncommittedChangesException(message)
