package com.ninetag.machum.screen.mainComposition

import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.external.AutoTagSyncUpdate
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.ProjectFile
import io.github.vinceglb.filekit.PlatformFile

/** 디렉터리 설정 저장 전후에 필요한 파일 저장과 관리 태그 동기화를 한 순서로 묶는다. */
internal class FolderSettingsService(
    private val fileManager: FileManager,
    private val flushPendingWrites: suspend (Set<FileKey>) -> Unit,
) {
    suspend fun update(
        project: PlatformFile,
        previousConfig: ProjectConfig,
        folderKey: FolderKey,
        folderConfig: FolderConfig,
    ): FolderSettingsUpdate? {
        val targetFiles = fileManager.listFolders(project)
            .filter { folder -> folderKey == FolderKey.Base || folder.key == folderKey }
            .flatMap { folder -> fileManager.listProjectFiles(folder) }

        flushPendingWrites(targetFiles.mapTo(mutableSetOf(), ProjectFile::key))

        val updatedConfig = fileManager.setFolderConfig(
            relativePath = folderKey.relativePath,
            folderConfig = folderConfig,
        ) ?: return null
        val autoTagUpdates = fileManager.synchronizeAutoTags(
            previousConfig = previousConfig,
            updatedConfig = updatedConfig,
            editedRelativePath = folderKey.relativePath,
        )
        return FolderSettingsUpdate(autoTagUpdates)
    }
}

internal data class FolderSettingsUpdate(
    val autoTagUpdates: List<AutoTagSyncUpdate>,
)
