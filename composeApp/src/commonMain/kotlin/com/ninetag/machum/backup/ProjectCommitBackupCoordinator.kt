package com.ninetag.machum.backup

import com.ninetag.machum.commit.ProjectCommitService
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException

fun interface CommitBackupEnqueuer {
    suspend fun enqueue(project: PlatformFile, commitId: String)
}

/** 로컬 커밋을 authority로 유지하고 선택적 원격 백업 등록 실패를 결과로만 돌려준다. */
class ProjectCommitBackupCoordinator(
    private val commitService: ProjectCommitService,
    private val backupEnqueuer: CommitBackupEnqueuer? = null,
) {
    suspend fun commit(
        project: PlatformFile,
        message: String,
    ): CommitAndBackupResult {
        val localCommit = commitService.commit(project, message)
        val enqueuer = backupEnqueuer
            ?: return CommitAndBackupResult(
                localCommit = localCommit,
                backup = BackupDispatchResult(BackupDispatchStatus.NOT_CONFIGURED),
            )

        val backup = try {
            enqueuer.enqueue(project, localCommit.commit.id)
            BackupDispatchResult(BackupDispatchStatus.QUEUED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BackupDispatchResult(
                status = BackupDispatchStatus.FAILED,
                errorMessage = error.message ?: "백업 작업을 등록하지 못했습니다.",
            )
        }
        return CommitAndBackupResult(localCommit, backup)
    }
}
