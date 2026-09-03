package com.ninetag.machum.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ninetag.machum.external.fromBookmarkDataWithValidate
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.bookmarkData
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class ProjectBackupBinding(
    val accountId: String,
    val projectId: String,
    val remoteFolderId: String,
    val lastUploadedCommitId: String? = null,
)

@Serializable
data class PendingProjectBackup(
    val accountId: String,
    val projectId: String,
    val projectBookmark: ByteArray,
    val targetCommitId: String,
    val enqueuedAtEpochMillis: Long,
    val attempts: Int = 0,
    val lastErrorMessage: String? = null,
) {
    fun resolveProject(): PlatformFile? =
        PlatformFile.fromBookmarkDataWithValidate(projectBookmark)
}

data class ProjectBackupQueueSnapshot(
    val bindings: List<ProjectBackupBinding>,
    val pending: List<PendingProjectBackup>,
)

/** 계정별 원격 folder 연결과 최신 commit 하나로 coalesce한 pending 작업을 영속화한다. */
class PersistentProjectBackupQueue(
    private val dataStore: DataStore<Preferences>,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun bind(
        accountId: String,
        projectId: String,
        remoteFolderId: String,
        lastUploadedCommitId: String? = null,
    ): ProjectBackupBinding {
        requireIdentifiers(accountId, projectId)
        require(remoteFolderId.isNotBlank()) { "remoteFolderId must not be blank" }
        val binding = ProjectBackupBinding(
            accountId = accountId,
            projectId = projectId,
            remoteFolderId = remoteFolderId,
            lastUploadedCommitId = lastUploadedCommitId,
        )
        update { state ->
            state.copy(
                bindings = (state.bindings.filterNot { it.matches(accountId, projectId) } + binding)
                    .sortedWith(bindingOrder),
            )
        }
        return binding
    }

    suspend fun unbind(accountId: String, projectId: String) {
        requireIdentifiers(accountId, projectId)
        update { state ->
            state.copy(
                bindings = state.bindings.filterNot { it.matches(accountId, projectId) },
                pending = state.pending.filterNot { it.matches(accountId, projectId) },
            )
        }
    }

    /** 앱 데이터 초기화 또는 전체 로그아웃 시 binding과 pending을 함께 제거한다. */
    suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(STATE_KEY) }
    }

    suspend fun binding(accountId: String, projectId: String): ProjectBackupBinding? =
        readState().bindings.find { it.matches(accountId, projectId) }

    suspend fun enqueue(
        accountId: String,
        projectId: String,
        project: PlatformFile,
        targetCommitId: String,
    ): PendingProjectBackup {
        requireIdentifiers(accountId, projectId)
        require(targetCommitId.isNotBlank()) { "targetCommitId must not be blank" }
        val projectBookmark = project.bookmarkData().bytes
        lateinit var queued: PendingProjectBackup
        update { state ->
            if (state.bindings.none { it.matches(accountId, projectId) }) {
                throw BackupNotConfiguredException(
                    "프로젝트의 원격 백업 폴더가 연결되지 않았습니다.",
                )
            }
            val previous = state.pending.find { it.matches(accountId, projectId) }
            queued = PendingProjectBackup(
                accountId = accountId,
                projectId = projectId,
                projectBookmark = projectBookmark,
                targetCommitId = targetCommitId,
                enqueuedAtEpochMillis = previous?.enqueuedAtEpochMillis ?: nowEpochMillis(),
            )
            state.copy(
                pending = (state.pending.filterNot { it.matches(accountId, projectId) } + queued)
                    .sortedWith(pendingOrder),
            )
        }
        return queued
    }

    suspend fun next(accountId: String? = null): PendingProjectBackup? = readState().pending
        .asSequence()
        .filter { pending -> accountId == null || pending.accountId == accountId }
        .sortedWith(pendingOrder)
        .firstOrNull()

    suspend fun markSucceeded(
        accountId: String,
        projectId: String,
        targetCommitId: String,
    ) {
        requireIdentifiers(accountId, projectId)
        update { state ->
            state.copy(
                bindings = state.bindings.map { binding ->
                    if (binding.matches(accountId, projectId)) {
                        binding.copy(lastUploadedCommitId = targetCommitId)
                    } else {
                        binding
                    }
                },
                pending = state.pending.filterNot { pending ->
                    pending.matches(accountId, projectId) &&
                        pending.targetCommitId == targetCommitId
                },
            )
        }
    }

    suspend fun markFailed(
        accountId: String,
        projectId: String,
        targetCommitId: String,
        errorMessage: String,
    ) {
        requireIdentifiers(accountId, projectId)
        update { state ->
            state.copy(
                pending = state.pending.map { pending ->
                    if (
                        pending.matches(accountId, projectId) &&
                        pending.targetCommitId == targetCommitId
                    ) {
                        pending.copy(
                            attempts = pending.attempts + 1,
                            lastErrorMessage = errorMessage,
                        )
                    } else {
                        pending
                    }
                },
            )
        }
    }

    suspend fun snapshot(): ProjectBackupQueueSnapshot = readState().toSnapshot()

    private suspend fun update(transform: (StoredBackupQueueState) -> StoredBackupQueueState) {
        dataStore.edit { preferences ->
            val current = decode(preferences[STATE_KEY])
            preferences[STATE_KEY] = encode(transform(current))
        }
    }

    private suspend fun readState(): StoredBackupQueueState =
        decode(dataStore.data.first()[STATE_KEY])

    private fun decode(content: String?): StoredBackupQueueState {
        if (content == null) return StoredBackupQueueState()
        return runCatching {
            json.decodeFromString(StoredBackupQueueState.serializer(), content)
        }.getOrElse { error ->
            throw BackupQueueStorageException("백업 대기열을 읽을 수 없습니다.", error)
        }
    }

    private fun encode(state: StoredBackupQueueState): String =
        json.encodeToString(StoredBackupQueueState.serializer(), state)

    private fun requireIdentifiers(accountId: String, projectId: String) {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        require(projectId.isNotBlank()) { "projectId must not be blank" }
    }

    private fun ProjectBackupBinding.matches(accountId: String, projectId: String): Boolean =
        this.accountId == accountId && this.projectId == projectId

    private fun PendingProjectBackup.matches(accountId: String, projectId: String): Boolean =
        this.accountId == accountId && this.projectId == projectId

    private fun StoredBackupQueueState.toSnapshot(): ProjectBackupQueueSnapshot =
        ProjectBackupQueueSnapshot(bindings = bindings, pending = pending)

    private companion object {
        val STATE_KEY = stringPreferencesKey("google_drive_backup_state_v1")
        val bindingOrder = compareBy(ProjectBackupBinding::accountId, ProjectBackupBinding::projectId)
        val pendingOrder = compareBy(
            PendingProjectBackup::enqueuedAtEpochMillis,
            PendingProjectBackup::accountId,
            PendingProjectBackup::projectId,
        )
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
private data class StoredBackupQueueState(
    val bindings: List<ProjectBackupBinding> = emptyList(),
    val pending: List<PendingProjectBackup> = emptyList(),
)

class BackupNotConfiguredException(message: String) : IllegalStateException(message)

class BackupQueueStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
