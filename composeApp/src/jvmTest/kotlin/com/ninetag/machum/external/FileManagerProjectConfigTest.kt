package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.ProjectConfig
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileManagerProjectConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun selectingProject_createsLiveConfig_andPersistsUpdates() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-project-config").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.pickProject(PlatformFile(projectDirectory))

            val loaded = withTimeout(5_000.milliseconds) {
                fileManager.projectConfig.filterNotNull().first()
            }
            assertEquals(FolderType.DEFAULT, loaded.folders[BASE_FOLDER_PATH]?.type)

            val configFile = File(projectDirectory, ".machum.json")
            assertTrue(configFile.isFile)
            val createdConfig = json.decodeFromString(ProjectConfig.serializer(), configFile.readText())
            assertEquals(FolderType.DEFAULT, createdConfig.folders[BASE_FOLDER_PATH]?.type)

            val updated = fileManager.setFolderConfig(
                relativePath = "Scene",
                folderConfig = FolderConfig(
                    type = FolderType.DEFAULT,
                    plotEnabled = true,
                    autoTags = listOf("장면"),
                ),
            )
            assertNotNull(updated)
            assertEquals(true, fileManager.projectConfig.value?.folders?.get("Scene")?.isPlot)

            val persisted = json.decodeFromString(ProjectConfig.serializer(), configFile.readText())
            assertEquals(
                FolderConfig(
                    type = FolderType.DEFAULT,
                    plotEnabled = true,
                    autoTags = listOf("장면"),
                ),
                persisted.folders["Scene"],
            )
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun legacyFolderTypes_areMigratedOnTheNextConfigWrite() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-legacy-folder-config").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val configFile = File(projectDirectory, ".machum.json").apply {
            writeText(
                """
                    {
                      "folders": {
                        "": { "type": "numbered", "autoTags": [] },
                        "Scene": { "type": "plot", "autoTags": ["장면"] }
                      },
                      "fileIds": {}
                    }
                """.trimIndent(),
            )
        }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.pickProject(PlatformFile(projectDirectory))

            val loaded = withTimeout(5_000.milliseconds) {
                fileManager.projectConfig.filterNotNull().first()
            }
            assertEquals(FolderType.DEFAULT, loaded.folders[BASE_FOLDER_PATH]?.type)
            assertEquals(true, loaded.folders["Scene"]?.isPlot)

            fileManager.setFolderConfig("Character", FolderConfig(type = FolderType.GENERAL))

            val persistedText = configFile.readText()
            assertTrue(persistedText.contains("\"type\": \"default\""))
            assertTrue(persistedText.contains("\"plotEnabled\": true"))
            assertTrue(!persistedText.contains("\"type\": \"numbered\""))
            assertTrue(!persistedText.contains("\"type\": \"plot\""))
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun emptyGeneralBaseCreatesAnUnnumberedFirstFile() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-general-base").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.pickProject(PlatformFile(projectDirectory))
            withTimeout(5_000.milliseconds) {
                fileManager.projectConfig.filterNotNull().first()
            }
            fileManager.setFolderConfig(
                relativePath = BASE_FOLDER_PATH,
                folderConfig = FolderConfig(type = FolderType.GENERAL),
            )

            val created = fileManager.setFile(PlatformFile(projectDirectory))

            assertEquals("제목.md", created.file.name)
            assertTrue(File(projectDirectory, "제목.md").isFile)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun createProjectFolder_createsDirectoryAndPersistsItsParameters() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-create-folder").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.pickProject(PlatformFile(projectDirectory))
            withTimeout(5_000.milliseconds) {
                fileManager.projectConfig.filterNotNull().first()
            }
            val folderConfig = FolderConfig(
                type = FolderType.DEFAULT,
                plotEnabled = true,
                autoTags = listOf("scene", "draft"),
            )

            val created = fileManager.createProjectFolder("Scene", folderConfig)

            assertNotNull(created)
            assertEquals("Scene", created.key.relativePath)
            assertTrue(File(projectDirectory, "Scene").isDirectory)
            assertEquals(folderConfig, fileManager.projectConfig.value?.folders?.get("Scene"))
            assertNull(fileManager.createProjectFolder("scene", FolderConfig()))
            assertNull(fileManager.createProjectFolder("../Outside", FolderConfig()))
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun resetClearsStoredAndRuntimeStateWithoutDeletingProjectFiles() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-reset").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.pickProject(PlatformFile(projectDirectory))
            withTimeout(5_000.milliseconds) {
                fileManager.projectConfig.filterNotNull().first()
            }
            val created = fileManager.setFile(PlatformFile(projectDirectory))

            fileManager.reset()

            assertEquals(Bookmarks(), fileManager.bookmarks.value)
            assertEquals(Bookmarks(), fileManager.getPreferences())
            assertNull(fileManager.projectConfig.value)
            assertTrue(created.file.isFile)
            assertTrue(File(projectDirectory, ".machum.json").isFile)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }
}
