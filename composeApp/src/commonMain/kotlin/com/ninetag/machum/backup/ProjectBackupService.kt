package com.ninetag.machum.backup

import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile

class ProjectBackupService(
    fileManager: FileManager,
    private val remoteStore: RemoteProjectBackupStore,
) {
    private val planSource = CommitBackupPlanSource(fileManager)
    private val identityStore = ProjectRepositoryIdentityStore(fileManager)

    suspend fun backup(
        project: PlatformFile,
        targetCommitId: String,
    ): ProjectBackupResult {
        val identity = identityStore.getOrCreate(project)
        return BackupKeyedMutexRegistry.withLock("backup:${identity.projectId}") {
            backupLocked(project, targetCommitId, identity)
        }
    }

    private suspend fun backupLocked(
        project: PlatformFile,
        targetCommitId: String,
        identity: ProjectRepositoryIdentity,
    ): ProjectBackupResult {
        val remoteHead = remoteStore.readHeadCommitId()
        if (remoteHead == targetCommitId) {
            return ProjectBackupResult(
                projectId = identity.projectId,
                targetCommitId = targetCommitId,
                createdObjectCount = 0,
                reusedObjectCount = 0,
                metadataFileCount = 0,
                workspaceFileCount = 0,
                alreadyCurrent = true,
            )
        }

        val plan = planSource.create(
            project = project,
            targetCommitId = targetCommitId,
            baseCommitId = remoteHead,
            repositoryIdentity = identity,
        )
        val currentHead = remoteStore.readHeadCommitId()
        if (currentHead != plan.baseCommitId) {
            throw BackupConcurrencyException(
                "백업 준비 중 원격 HEAD가 변경되었습니다: ${plan.baseCommitId} → $currentHead",
            )
        }

        var created = 0
        var reused = 0
        plan.immutableObjects.forEach { objectToStore ->
            when (remoteStore.putImmutable(objectToStore)) {
                ImmutablePutResult.CREATED -> created += 1
                ImmutablePutResult.ALREADY_PRESENT -> reused += 1
            }
        }
        val headBeforeMutablePublish = remoteStore.readHeadCommitId()
        if (headBeforeMutablePublish != plan.baseCommitId) {
            throw BackupConcurrencyException(
                "원격 작업본 게시 전 HEAD가 변경되었습니다: " +
                    "${plan.baseCommitId} → $headBeforeMutablePublish",
            )
        }
        remoteStore.upsertMetadata(plan.targetCommitId, plan.metadata)
        remoteStore.mirrorWorkspace(plan.targetCommitId, plan.workspace)
        remoteStore.updateHead(plan.baseCommitId, plan.head)

        return ProjectBackupResult(
            projectId = plan.projectId,
            targetCommitId = plan.targetCommitId,
            createdObjectCount = created,
            reusedObjectCount = reused,
            metadataFileCount = plan.metadata.size,
            workspaceFileCount = plan.workspace.size,
        )
    }
}
