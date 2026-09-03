package com.ninetag.machum.backup

import com.ninetag.machum.commit.CommitResult

enum class ImmutableBackupObjectKind {
    BLOB,
    TREE,
    COMMIT,
}

/** 원격에서 경로와 contentHash를 기준으로 중복 저장하지 않는 불변 객체. */
data class ImmutableBackupObject(
    val kind: ImmutableBackupObjectKind,
    val relativePath: String,
    val content: String,
    val contentHash: String,
)

/** 원격의 읽기 가능한 현재 작업본을 구성하는 항목. 본문은 history blobHash로 참조한다. */
data class BackupWorkspaceEntry(
    val fileId: String,
    val relativePath: String,
    val blobHash: String,
)

data class MutableBackupObject(
    val relativePath: String,
    val content: String,
    val contentHash: String,
)

/**
 * [baseCommitId]에서 [targetCommitId]로 원격 백업을 전진시키는 완전한 계획.
 *
 * immutableObjects → workspace → head 순서로 적용해야 한다. HEAD를 마지막에 쓰면
 * 중간 실패 시 다음 재시도에서 기존 원격 기준점을 다시 사용할 수 있다.
 */
data class CommitBackupPlan(
    val projectId: String,
    val baseCommitId: String?,
    val targetCommitId: String,
    val immutableObjects: List<ImmutableBackupObject>,
    val metadata: List<MutableBackupObject>,
    val workspace: List<BackupWorkspaceEntry>,
    val head: MutableBackupObject,
)

enum class ImmutablePutResult {
    CREATED,
    ALREADY_PRESENT,
}

data class ProjectBackupResult(
    val projectId: String,
    val targetCommitId: String,
    val createdObjectCount: Int,
    val reusedObjectCount: Int,
    val metadataFileCount: Int,
    val workspaceFileCount: Int,
    val alreadyCurrent: Boolean = false,
)

enum class BackupDispatchStatus {
    NOT_CONFIGURED,
    QUEUED,
    FAILED,
}

data class BackupDispatchResult(
    val status: BackupDispatchStatus,
    val errorMessage: String? = null,
)

data class CommitAndBackupResult(
    val localCommit: CommitResult,
    val backup: BackupDispatchResult,
)

class BackupHistoryMismatchException(message: String) : IllegalStateException(message)

class BackupConcurrencyException(message: String) : IllegalStateException(message)
