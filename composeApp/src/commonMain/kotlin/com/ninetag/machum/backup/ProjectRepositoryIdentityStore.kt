package com.ninetag.machum.backup

import com.ninetag.machum.commit.createCommitStorageFile
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.createFolder
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProjectRepositoryIdentity(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: String,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported repository schema" }
        require(projectId.isNotBlank()) { "projectId must not be blank" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

class ProjectRepositoryIdentityStore(
    private val fileManager: FileManager,
) {
    suspend fun getOrCreate(project: PlatformFile): ProjectRepositoryIdentity =
        BackupKeyedMutexRegistry.withLock("identity:${project}") {
            withContext(Dispatchers.IO) {
                val repositoryDirectory = fileManager.createFolder(project, STORE_DIRECTORY)
                    ?: throw ProjectRepositoryIdentityException(
                        "프로젝트에 $STORE_DIRECTORY 저장소를 만들 수 없습니다.",
                    )
                val existing = repositoryDirectory.list().find { child ->
                    !child.isDirectory() && child.name == IDENTITY_FILE
                }
                if (existing != null) return@withContext decode(existing.readString())

                val identity = ProjectRepositoryIdentity(projectId = newProjectBackupId())
                val created = fileManager.createCommitStorageFile(
                    parentDirectory = repositoryDirectory,
                    name = IDENTITY_FILE,
                    mimeType = "application/json",
                ) ?: throw ProjectRepositoryIdentityException(
                    "프로젝트 백업 ID 파일을 만들 수 없습니다.",
                )
                if (created.name != IDENTITY_FILE) {
                    throw ProjectRepositoryIdentityException(
                        "저장소가 프로젝트 백업 ID 파일명을 변경했습니다: ${created.name}",
                    )
                }
                created.writeString(encode(identity))
                identity
            }
        }

    internal fun encode(identity: ProjectRepositoryIdentity): String =
        json.encodeToString(ProjectRepositoryIdentity.serializer(), identity)

    private fun decode(content: String): ProjectRepositoryIdentity = runCatching {
        json.decodeFromString(ProjectRepositoryIdentity.serializer(), content)
    }.getOrElse { error ->
        throw ProjectRepositoryIdentityException(
            "프로젝트 백업 ID 파일을 읽을 수 없습니다.",
            error,
        )
    }

    private companion object {
        const val STORE_DIRECTORY = ".machum"
        const val IDENTITY_FILE = "repository.json"

        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

class ProjectRepositoryIdentityException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
