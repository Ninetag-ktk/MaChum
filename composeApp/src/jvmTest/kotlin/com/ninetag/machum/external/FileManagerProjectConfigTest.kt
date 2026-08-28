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
            assertEquals(FolderType.NUMBERED, loaded.folders[BASE_FOLDER_PATH]?.type)

            val configFile = File(projectDirectory, ".machum.json")
            assertTrue(configFile.isFile)
            val createdConfig = json.decodeFromString(ProjectConfig.serializer(), configFile.readText())
            assertEquals(FolderType.NUMBERED, createdConfig.folders[BASE_FOLDER_PATH]?.type)

            val updated = fileManager.setFolderConfig(
                relativePath = "Scene",
                folderConfig = FolderConfig(FolderType.PLOT, listOf("장면")),
            )
            assertNotNull(updated)
            assertEquals(FolderType.PLOT, fileManager.projectConfig.value?.folders?.get("Scene")?.type)

            val persisted = json.decodeFromString(ProjectConfig.serializer(), configFile.readText())
            assertEquals(FolderConfig(FolderType.PLOT, listOf("장면")), persisted.folders["Scene"])
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }
}
