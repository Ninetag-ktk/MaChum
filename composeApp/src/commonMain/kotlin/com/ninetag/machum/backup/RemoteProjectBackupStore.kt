package com.ninetag.machum.backup

/** Google Drive 등 프로젝트 하나의 원격 백업 위치가 구현할 최소 계약. */
interface RemoteProjectBackupStore {
    suspend fun readHeadCommitId(): String?

    /** 같은 경로의 같은 contentHash는 성공한 no-op이어야 한다. */
    suspend fun putImmutable(objectToStore: ImmutableBackupObject): ImmutablePutResult

    /** commit 이력 밖의 최신 복구 metadata를 target commit 기준으로 교체한다. */
    suspend fun upsertMetadata(
        targetCommitId: String,
        entries: List<MutableBackupObject>,
    )

    /** fileId 기준으로 현재 작업본을 일치시키며 여러 번 호출해도 같은 결과여야 한다. */
    suspend fun mirrorWorkspace(
        targetCommitId: String,
        entries: List<BackupWorkspaceEntry>,
    )

    /** 현재 HEAD가 [expectedCommitId]일 때만 새 HEAD를 게시한다. */
    suspend fun updateHead(
        expectedCommitId: String?,
        head: MutableBackupObject,
    )
}
