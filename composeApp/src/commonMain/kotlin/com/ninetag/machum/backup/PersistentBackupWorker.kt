package com.ninetag.machum.backup

import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException

class PersistentCommitBackupEnqueuer(
    private val accountId: String,
    private val identityStore: ProjectRepositoryIdentityStore,
    private val queue: PersistentProjectBackupQueue,
) : CommitBackupEnqueuer {
    override suspend fun enqueue(project: PlatformFile, commitId: String) {
        val identity = identityStore.getOrCreate(project)
        queue.enqueue(
            accountId = accountId,
            projectId = identity.projectId,
            project = project,
            targetCommitId = commitId,
        )
    }
}

fun interface RemoteProjectBackupStoreProvider {
    suspend fun open(binding: ProjectBackupBinding): RemoteProjectBackupStore
}

sealed interface BackupWorkResult {
    data object NoPendingWork : BackupWorkResult

    data class Completed(
        val pending: PendingProjectBackup,
        val backup: ProjectBackupResult,
    ) : BackupWorkResult

    data class Failed(
        val pending: PendingProjectBackup,
        val errorMessage: String,
    ) : BackupWorkResult
}

/** 앱 실행 중 한 건을 처리하는 worker core. 호출 반복·다음 실행 재개 정책은 UI/application 계층이 담당한다. */
class PersistentBackupWorker(
    private val fileManager: FileManager,
    private val queue: PersistentProjectBackupQueue,
    private val remoteStoreProvider: RemoteProjectBackupStoreProvider,
) {
    suspend fun runNext(accountId: String? = null): BackupWorkResult {
        val pending = queue.next(accountId) ?: return BackupWorkResult.NoPendingWork
        val project = pending.resolveProject()
            ?: return fail(pending, "프로젝트 접근 권한을 복원하지 못했습니다.")
        val identity = try {
            ProjectRepositoryIdentityStore(fileManager).getOrCreate(project)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return fail(pending, error.message ?: "프로젝트 백업 ID를 읽지 못했습니다.")
        }
        if (identity.projectId != pending.projectId) {
            return fail(pending, "대기열의 프로젝트 ID와 로컬 프로젝트 ID가 다릅니다.")
        }
        val binding = queue.binding(pending.accountId, pending.projectId)
            ?: return fail(pending, "프로젝트의 원격 백업 폴더 연결을 찾지 못했습니다.")

        return try {
            val remoteStore = remoteStoreProvider.open(binding)
            val result = ProjectBackupService(fileManager, remoteStore).backup(
                project = project,
                targetCommitId = pending.targetCommitId,
            )
            queue.markSucceeded(
                accountId = pending.accountId,
                projectId = pending.projectId,
                targetCommitId = pending.targetCommitId,
            )
            BackupWorkResult.Completed(pending, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(pending, error.message ?: "프로젝트 백업에 실패했습니다.")
        }
    }

    private suspend fun fail(
        pending: PendingProjectBackup,
        errorMessage: String,
    ): BackupWorkResult.Failed {
        queue.markFailed(
            accountId = pending.accountId,
            projectId = pending.projectId,
            targetCommitId = pending.targetCommitId,
            errorMessage = errorMessage,
        )
        return BackupWorkResult.Failed(pending, errorMessage)
    }
}
