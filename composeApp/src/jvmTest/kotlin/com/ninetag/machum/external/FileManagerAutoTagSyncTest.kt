package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.ProjectConfig
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileManagerAutoTagSyncTest {

    @Test
    fun baseAndFolderTagChangesUpdateExistingFilesWithoutRenamingThem() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-auto-tag-sync").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val characterDirectory = File(projectDirectory, "Character").apply { mkdirs() }
        val rootFile = File(projectDirectory, "0. Root.md").apply {
            writeText(noteWithTags("수동-root", "기존-base"))
        }
        val characterFile = File(characterDirectory, "Hero.md").apply {
            writeText(noteWithTags("수동-character", "기존-base", "기존-folder"))
        }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(projectDirectory)))
            val initialConfig = ProjectConfig(
                folders = mapOf(
                    BASE_FOLDER_PATH to FolderConfig(autoTags = listOf("기존-base")),
                    "Character" to FolderConfig(autoTags = listOf("기존-folder")),
                ),
            )
            val baseUpdated = initialConfig.copy(
                folders = initialConfig.folders + (
                    BASE_FOLDER_PATH to FolderConfig(autoTags = listOf("신규-base"))
                ),
            )

            fileManager.synchronizeAutoTags(initialConfig, baseUpdated, BASE_FOLDER_PATH)

            assertEquals(
                listOf("Project", "수동-root", "신규-base"),
                fileManager.readMarkdown(PlatformFile(rootFile)).tags,
            )
            assertEquals(
                listOf("Project", "수동-character", "신규-base", "기존-folder"),
                fileManager.readMarkdown(PlatformFile(characterFile)).tags,
            )

            val folderUpdated = baseUpdated.copy(
                folders = baseUpdated.folders + (
                    "Character" to FolderConfig(autoTags = listOf("신규-folder"))
                ),
            )
            fileManager.synchronizeAutoTags(baseUpdated, folderUpdated, "Character")

            assertEquals(
                listOf("Project", "수동-root", "신규-base"),
                fileManager.readMarkdown(PlatformFile(rootFile)).tags,
            )
            assertEquals(
                listOf("Project", "수동-character", "신규-base", "신규-folder"),
                fileManager.readMarkdown(PlatformFile(characterFile)).tags,
            )
            assertEquals("0. Root.md", rootFile.name)
            assertEquals("Hero.md", characterFile.name)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    private fun noteWithTags(vararg tags: String): String = buildString {
        appendLine("---")
        appendLine("tags:")
        tags.forEach { appendLine("  - $it") }
        appendLine("---")
        appendLine()
        append("본문")
    }
}
