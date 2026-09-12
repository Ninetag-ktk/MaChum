package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.ProjectFolder
import io.github.vinceglb.filekit.PlatformFile

/** 읽기 실패는 변경 전에 처리한다. 본문 쓰기는 호출자의 기존 pending 저장 경로만 사용한다. */
internal class FolderSettingsService(private val fileManager: FileManager) {
    suspend fun prepare(
        project: PlatformFile,
        previousConfig: ProjectConfig,
        folderKey: FolderKey,
        updatedName: String,
        folderConfig: FolderConfig,
    ): FolderSettingsPlan? {
        val folders = fileManager.listFolders(project)
        val folder = folders.find { it.key == folderKey } ?: return null
        val files = folders.filter { folderKey == FolderKey.Base || it.key == folderKey }
            .flatMap { fileManager.listProjectFiles(it) }
        val snapshots = files.associate { file ->
            file.key to FolderSettingsFile(
                projectFile = file,
                noteFile = fileManager.readMarkdown(file.platformFile),
                modified = fileManager.lastModified(file.platformFile),
            )
        }
        return FolderSettingsPlan(folder, previousConfig, updatedName.trim(), folderConfig, snapshots)
    }

    /** prepare 이후 쓰기 fence 안에서 호출하고, 성공 결과는 suspend 없이 runtime에 적용한다. */
    suspend fun commit(plan: FolderSettingsPlan): FolderSettingsUpdate? {
        val oldKey = plan.folder.key
        if (oldKey != FolderKey.Base && plan.updatedName != oldKey.relativePath) {
            val result = fileManager.renameProjectFolder(plan.folder, plan.updatedName, plan.folderConfig)
                ?: return null
            return FolderSettingsUpdate(result.projectFolder, result.projectConfig, result.filesByPreviousKey)
        }
        val updatedConfig = fileManager.setFolderConfig(oldKey.relativePath, plan.folderConfig) ?: return null
        return FolderSettingsUpdate(plan.folder, updatedConfig, plan.files.mapValues { it.value.projectFile })
    }
}

internal data class FolderSettingsPlan(
    val folder: ProjectFolder,
    val previousConfig: ProjectConfig,
    val updatedName: String,
    val folderConfig: FolderConfig,
    val files: Map<FileKey, FolderSettingsFile>,
)

internal data class FolderSettingsFile(
    val projectFile: ProjectFile,
    val noteFile: NoteFile,
    val modified: Long?,
)

internal data class FolderSettingsUpdate(
    val folder: ProjectFolder,
    val projectConfig: ProjectConfig,
    val filesByPreviousKey: Map<FileKey, ProjectFile>,
)
