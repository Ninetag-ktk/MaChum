package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.serialization.Serializable
import com.ninetag.machum.entity.FolderConfig

/** Vault 직속 폴더의 사용 방식. 미설정 폴더도 프로젝트 선택 목록에 표시한다. */
enum class WorkspaceKind { PROJECT, GENERAL }

enum class WorkspaceSetup { PROJECT, GENERAL, NEEDS_CONFIRMATION }

data class WorkspaceOpenRequest(
    val directory: PlatformFile,
    val busy: Boolean = false,
    val errorMessage: String? = null,
)

data class WorkspaceDirectoryLists(
    /** 관리 Project와 아직 사용 방식을 고르지 않은 후보. */
    val projectChoices: List<PlatformFile> = emptyList(),
    /** 사용자가 일반 폴더로 지정한 Vault 직속 디렉터리. */
    val generalFolders: List<PlatformFile> = emptyList(),
)

/**
 * Vault와 함께 이동해야 하는 최소 탐색 설정.
 *
 * Vault 직속 디렉터리 이름만 저장하므로 Vault의 실제 위치가 바뀌어도 다시 사용할 수 있다.
 */
@Serializable
data class VaultConfig(
    val generalFolders: List<String> = emptyList(),
    val lastProject: String? = null,
    val suspendedProjects: Map<String, SuspendedProjectSettings> = emptyMap(),
)

@Serializable
data class SuspendedProjectSettings(
    val projectName: String,
    val workspaceIdentity: String,
    val originalConfigText: String,
    val foldersByIdentity: Map<String, FolderConfig> = emptyMap(),
)

@Serializable
internal data class WorkspaceFolderIdentity(val machumFolderId: String)
