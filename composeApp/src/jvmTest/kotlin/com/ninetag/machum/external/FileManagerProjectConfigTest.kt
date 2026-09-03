package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.DEFAULT_PROJECT_FOLDERS
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
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
    fun creatingProject_createsOrderedDefaultFoldersAndPersistsTheirSettings() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-new-project").toFile()
        val vaultDirectory = File(testRoot, "Vault").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(Bookmarks(vaultData = PlatformFile(vaultDirectory)))

            val created = fileManager.setProject("New Project")

            assertNotNull(created)
            val projectDirectory = File(vaultDirectory, "New Project")
            assertEquals(
                DEFAULT_PROJECT_FOLDERS.map { it.name },
                projectDirectory.listFiles()!!
                    .filter(File::isDirectory)
                    .sortedBy { it.name }
                    .map { it.name },
            )
            val persisted = json.decodeFromString(
                ProjectConfig.serializer(),
                File(projectDirectory, ".machum.json").readText(),
            )
            assertEquals(
                DEFAULT_PROJECT_FOLDERS.associate { it.name to it.config },
                persisted.folders.filterKeys { it != BASE_FOLDER_PATH },
            )
            assertEquals(
                FolderConfig(type = FolderType.DEFAULT, plotEnabled = true),
                persisted.folders[BASE_FOLDER_PATH],
            )

            val existingMarker = File(projectDirectory, "keep.md").apply { writeText("keep") }
            assertNull(fileManager.setProject("New Project"))
            assertEquals("keep", existingMarker.readText())
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
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
            assertEquals(
                FolderConfig(type = FolderType.DEFAULT, plotEnabled = true),
                loaded.folders[BASE_FOLDER_PATH],
            )

            val configFile = File(projectDirectory, ".machum.json")
            assertTrue(configFile.isFile)
            val createdConfig = json.decodeFromString(ProjectConfig.serializer(), configFile.readText())
            assertEquals(
                FolderConfig(type = FolderType.DEFAULT, plotEnabled = true),
                createdConfig.folders[BASE_FOLDER_PATH],
            )

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
    fun emptyDefaultPlotBaseCreatesOneBasedPrologueFile() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-plot-base").toFile()
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

            assertEquals("0-1. 제목.md", created.file.name)
            assertEquals(
                PlotStage.PROLOGUE,
                NoteFile.parse(created.file.readText()).plotStage,
            )
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun existingDefaultPlotBaseSelectsTheNumericallyLatestPlotFile() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-existing-plot-base").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        File(projectDirectory, "6-2. Earlier.md").writeText(
            NoteFile.parse("earlier").withPlotStage(PlotStage.EPILOGUE).inject(),
        )
        val latest = File(projectDirectory, "6-10. Latest.md").apply {
            writeText(NoteFile.parse("latest").withPlotStage(PlotStage.EPILOGUE).inject())
        }
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

            val selected = fileManager.setFile(PlatformFile(projectDirectory))

            assertEquals(latest.absolutePath, selected.file.absolutePath)
            assertEquals("6-10. Latest.md", fileManager.bookmarks.value.fileRelativePath)
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
    fun renameProject_movesDirectoryBookmarksAndProjectTags() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-rename-project").toFile()
        val vaultDirectory = File(testRoot, "Vault").apply { mkdirs() }
        val projectDirectory = File(vaultDirectory, "Old Project").apply { mkdirs() }
        val characterDirectory = File(projectDirectory, "Character").apply { mkdirs() }
        val rootFile = File(projectDirectory, "0. Opening.md").apply {
            writeText("---\nid: root-id\ntags:\n  - Old_Project\n  - 사용자\n---\n\nroot")
        }
        val heroFile = File(characterDirectory, "Hero.md").apply {
            writeText("---\nid: hero-id\ntags:\n  - Old_Project\n  - 캐릭터\n---\n\nhero")
        }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(
                Bookmarks(
                    vaultData = PlatformFile(vaultDirectory),
                    projectData = PlatformFile(projectDirectory),
                    fileData = PlatformFile(heroFile),
                    fileRelativePath = "Character/Hero.md",
                )
            )

            val renamed = fileManager.renameProject(PlatformFile(projectDirectory), "New Project")

            assertNotNull(renamed)
            val renamedDirectory = File(vaultDirectory, "New Project")
            assertTrue(renamedDirectory.isDirectory)
            assertTrue(!projectDirectory.exists())
            assertEquals(renamedDirectory.absolutePath, renamed.file.absolutePath)
            assertEquals(renamed.toString(), fileManager.bookmarks.value.projectData.toString())
            assertEquals("Character/Hero.md", fileManager.bookmarks.value.fileRelativePath)
            assertEquals(
                File(renamedDirectory, "Character/Hero.md").absolutePath,
                fileManager.bookmarks.value.fileData?.file?.absolutePath,
            )
            assertEquals(
                listOf("New_Project", "사용자"),
                NoteFile.parse(File(renamedDirectory, rootFile.name).readText()).tags,
            )
            assertEquals(
                listOf("New_Project", "캐릭터"),
                NoteFile.parse(File(renamedDirectory, "Character/Hero.md").readText()).tags,
            )
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun renameProject_rejectsInvalidDuplicateAndCaseOnlyNames() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-reject-project-rename").toFile()
        val vaultDirectory = File(testRoot, "Vault").apply { mkdirs() }
        val projectDirectory = File(vaultDirectory, "Project").apply { mkdirs() }
        File(vaultDirectory, "Existing").mkdirs()
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            val project = PlatformFile(projectDirectory)
            fileManager.setPreferences(
                Bookmarks(
                    vaultData = PlatformFile(vaultDirectory),
                    projectData = project,
                )
            )

            assertNull(fileManager.renameProject(project, "Existing"))
            assertNull(fileManager.renameProject(project, "../Outside"))
            assertNull(fileManager.renameProject(project, "project"))
            assertTrue(projectDirectory.isDirectory)
            assertEquals(project.toString(), fileManager.bookmarks.value.projectData.toString())
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun renameProjectFolder_movesDirectoryConfigIdsAndSelectedBookmark() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-rename-folder").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val characterDirectory = File(projectDirectory, "Character").apply { mkdirs() }
        val heroFile = File(characterDirectory, "Hero.md").apply { writeText("hero") }
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
                type = FolderType.GENERAL,
                autoTags = listOf("캐릭터"),
            )
            fileManager.updateProjectConfig { config ->
                config.copy(
                    folders = config.folders + ("Character" to folderConfig),
                    fileIds = mapOf("hero-id" to "Character/Hero.md"),
                )
            }
            fileManager.setPreferences(
                fileManager.bookmarks.value.copy(
                    fileData = PlatformFile(heroFile),
                    fileRelativePath = "Character/Hero.md",
                )
            )

            val renamed = fileManager.renameProjectFolder(
                folder = ProjectFolder(FolderKey.of("Character"), PlatformFile(characterDirectory)),
                newName = "3. Character",
                folderConfig = folderConfig,
            )

            assertNotNull(renamed)
            assertTrue(File(projectDirectory, "3. Character/Hero.md").isFile)
            assertTrue(!characterDirectory.exists())
            assertNull(fileManager.projectConfig.value?.folders?.get("Character"))
            assertEquals(folderConfig, fileManager.projectConfig.value?.folders?.get("3. Character"))
            assertEquals(
                "3. Character/Hero.md",
                fileManager.projectConfig.value?.fileIds?.get("hero-id"),
            )
            assertEquals("3. Character/Hero.md", fileManager.bookmarks.value.fileRelativePath)
            assertEquals("3. Character/Hero.md", renamed.selectedFileKey?.relativePath)

            File(projectDirectory, "Existing").mkdirs()
            assertNull(
                fileManager.renameProjectFolder(
                    folder = renamed.projectFolder,
                    newName = "Existing",
                    folderConfig = folderConfig,
                )
            )
            assertTrue(File(projectDirectory, "3. Character/Hero.md").isFile)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun deleteProjectFolder_removesMarkdownFilesConfigIdsAndSelectedBookmark() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-delete-folder").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val characterDirectory = File(projectDirectory, "Character").apply { mkdirs() }
        val heroFile = File(characterDirectory, "Hero.md").apply { writeText("hero") }
        File(characterDirectory, "Villain.md").writeText("villain")
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
            val folderConfig = FolderConfig(type = FolderType.GENERAL)
            fileManager.updateProjectConfig { config ->
                config.copy(
                    folders = config.folders + ("Character" to folderConfig),
                    fileIds = mapOf(
                        "hero" to "Character/Hero.md",
                        "root" to "0. Opening.md",
                    ),
                )
            }
            fileManager.setPreferences(
                fileManager.bookmarks.value.copy(
                    fileData = PlatformFile(heroFile),
                    fileRelativePath = "Character/Hero.md",
                )
            )

            val preview = fileManager.inspectProjectFolderDeletion(FolderKey.of("Character"))

            assertNotNull(preview)
            assertTrue(preview.canDelete)
            assertEquals(2, preview.markdownFiles.size)
            val deleted = fileManager.deleteProjectFolder(FolderKey.of("Character"))
            assertNotNull(deleted)
            assertTrue(!characterDirectory.exists())
            assertNull(fileManager.projectConfig.value?.folders?.get("Character"))
            assertEquals(mapOf("root" to "0. Opening.md"), fileManager.projectConfig.value?.fileIds)
            assertNull(fileManager.bookmarks.value.fileData)
            assertNull(fileManager.bookmarks.value.fileRelativePath)
            assertEquals(PlatformFile(projectDirectory).toString(), fileManager.bookmarks.value.projectData.toString())
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun deleteProjectFolder_refusesFoldersContainingUnsupportedEntries() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-block-delete-folder").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val sceneDirectory = File(projectDirectory, "Scene").apply { mkdirs() }
        File(sceneDirectory, "Opening.md").writeText("opening")
        File(sceneDirectory, "Act1").mkdirs()
        File(sceneDirectory, "cover.png").writeText("image")
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
            fileManager.setFolderConfig("Scene", FolderConfig(plotEnabled = true))

            val preview = fileManager.inspectProjectFolderDeletion(FolderKey.of("Scene"))

            assertNotNull(preview)
            assertTrue(!preview.canDelete)
            assertEquals(listOf("Act1", "cover.png"), preview.unsupportedEntries)
            assertNull(fileManager.deleteProjectFolder(FolderKey.of("Scene")))
            assertTrue(sceneDirectory.isDirectory)
            assertNotNull(fileManager.projectConfig.value?.folders?.get("Scene"))
            Unit
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
