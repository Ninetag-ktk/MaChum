package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.DEFAULT_PROJECT_FOLDERS
import com.ninetag.machum.entity.DocumentPropertyDefinitionChange
import com.ninetag.machum.entity.DocumentPropertyType
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.documentPropertyDefaults
import com.ninetag.machum.entity.withDefaultBaseFolder
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileManagerProjectConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun propertyDefinitionWritePreservesUnknownFieldsAndRejectsExternalKnownChanges() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-property-config").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        File(projectDirectory, "Drafts").mkdirs()
        val existingDocument = File(projectDirectory, "Drafts/Existing.md").apply { writeText("existing body") }
        val configFile = File(projectDirectory, ".machum.json").apply {
            writeText(
                """{"folders":{"":{"type":"default","plotEnabled":true,"autoTags":[],"defaultPropertyKeys":[],"futureFolder":"keep"},"Drafts":{"type":"general","plotEnabled":false,"autoTags":[],"defaultPropertyKeys":[]}},"fileIds":{},"propertyTypes":{},"futureRoot":{"value":1}}""",
            )
        }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.openProjectThroughWorkspaceFlow(testRoot, projectDirectory, expectConfirmation = false)
            withTimeout(5_000.milliseconds) { fileManager.projectConfig.filterNotNull().first() }
            val existingAfterWorkspaceOpen = existingDocument.readText()

            fileManager.updateDocumentPropertyDefinition(
                "Drafts",
                DocumentPropertyDefinitionChange(null, "rating", DocumentPropertyType.NUMBER),
            )
            assertEquals(existingAfterWorkspaceOpen, existingDocument.readText())

            val raw = Json.parseToJsonElement(configFile.readText()).jsonObject
            assertEquals(1, raw.getValue("futureRoot").jsonObject.getValue("value").jsonPrimitive.content.toInt())
            assertEquals(
                "keep",
                raw.getValue("folders").jsonObject.getValue("").jsonObject
                    .getValue("futureFolder").jsonPrimitive.content,
            )
            assertEquals(
                "number",
                raw.getValue("propertyTypes").jsonObject.getValue("rating").jsonPrimitive.content,
            )

            fileManager.updateDocumentPropertyDefinitions(
                "Drafts",
                listOf(
                    DocumentPropertyDefinitionChange(null, "aliases", DocumentPropertyType.LIST),
                    DocumentPropertyDefinitionChange(null, "source", DocumentPropertyType.TEXT),
                    DocumentPropertyDefinitionChange(null, "id", DocumentPropertyType.TEXT),
                    DocumentPropertyDefinitionChange(null, "plot", DocumentPropertyType.TEXT),
                    DocumentPropertyDefinitionChange(null, "tags", DocumentPropertyType.TAGS),
                ),
            )
            val defaults = fileManager.projectConfig.value!!.documentPropertyDefaults("Drafts")
            assertEquals(listOf("rating", "aliases", "source"), defaults.defaultPropertyKeys)
            assertTrue(defaults.propertyTypes.keys.none { it in setOf("id", "plot", "tags") })
            val drafts = fileManager.listFolders(PlatformFile(projectDirectory)).single { it.key.relativePath == "Drafts" }
            val created = assertNotNull(fileManager.createProjectFile(drafts, "New note"))
            val createdRaw = created.platformFile.readString()
            assertTrue(Regex("(?m)^rating:$").containsMatchIn(createdRaw))
            assertTrue(Regex("(?m)^aliases:$").containsMatchIn(createdRaw))
            assertTrue(Regex("(?m)^source:$").containsMatchIn(createdRaw))
            assertEquals(1, Regex("(?m)^id:").findAll(createdRaw).count())
            assertFalse(Regex("(?m)^plot:").containsMatchIn(createdRaw))
            assertEquals(listOf("Project"), NoteFile.parse(createdRaw).tags)
            assertFalse(createdRaw.contains("New note"), "file name must not be copied into aliases")

            val changedBeforeCreation = fileManager.projectConfig.value!!.let { current ->
                current.copy(
                    folders = current.folders + (
                        "Drafts" to current.folders.getValue("Drafts").copy(
                            defaultPropertyKeys = current.folders.getValue("Drafts").defaultPropertyKeys + "late",
                        )
                    ),
                    propertyTypes = current.propertyTypes + ("late" to DocumentPropertyType.TEXT),
                )
            }
            configFile.writeText(Json { encodeDefaults = true }.encodeToString(changedBeforeCreation))
            val createdAfterExternalChange = assertNotNull(fileManager.createProjectFile(drafts, "After external change"))
            assertTrue(Regex("(?m)^late:$").containsMatchIn(createdAfterExternalChange.platformFile.readString()))
            assertEquals(
                changedBeforeCreation.withDefaultBaseFolder(),
                fileManager.projectConfig.value,
                "file creation must refresh the live config from disk before applying defaults",
            )

            val external = fileManager.projectConfig.value!!.copy(
                propertyTypes = fileManager.projectConfig.value!!.propertyTypes +
                    ("external" to DocumentPropertyType.TEXT),
            )
            val externalRaw = Json { encodeDefaults = true }.encodeToString(external)
            configFile.writeText(externalRaw)
            assertFailsWith<IllegalStateException> {
                fileManager.updateDocumentPropertyDefinition(
                    "Drafts",
                    DocumentPropertyDefinitionChange(null, "after-external", DocumentPropertyType.TEXT),
                )
            }
            assertEquals(externalRaw, configFile.readText())
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
        Unit
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )

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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
                expectConfirmation = false,
            )

            val loaded = withTimeout(5_000.milliseconds) {
                fileManager.projectConfig.filterNotNull().first()
            }
            assertEquals(FolderType.DEFAULT, loaded.folders[BASE_FOLDER_PATH]?.type)
            assertEquals(true, loaded.folders["Scene"]?.isPlot)

            fileManager.setFolderConfig("Character", FolderConfig(type = FolderType.GENERAL))

            val persistedText = configFile.readText()
            val persisted = Json { ignoreUnknownKeys = true }.decodeFromString<ProjectConfig>(persistedText)
            assertEquals(FolderType.DEFAULT, persisted.folders[BASE_FOLDER_PATH]?.type)
            assertEquals(true, persisted.folders["Scene"]?.plotEnabled)
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
            withTimeout(5_000.milliseconds) {
                fileManager.projectConfig.filterNotNull().first()
            }
            fileManager.updateDocumentPropertyDefinition(
                BASE_FOLDER_PATH,
                DocumentPropertyDefinitionChange(null, "aliases", DocumentPropertyType.LIST),
            )

            val created = fileManager.setFile(PlatformFile(projectDirectory))

            assertEquals("0-1. 제목.md", created.file.name)
            assertEquals(
                PlotStage.PROLOGUE,
                NoteFile.parse(created.file.readText()).plotStage,
            )
            assertTrue(Regex("(?m)^aliases:$").containsMatchIn(created.file.readText()))
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
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
            assertEquals("scene_1", assertNotNull(fileManager.createProjectFolder("scene", FolderConfig())).key.relativePath)
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
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
            val configFile = File(projectDirectory, ".machum.json")
            val configRaw = Json.parseToJsonElement(configFile.readText()).jsonObject
            val foldersRaw = configRaw.getValue("folders").jsonObject
            val characterRaw = foldersRaw.getValue("Character").jsonObject
            configFile.writeText(Json.encodeToString(
                JsonElement.serializer(),
                JsonObject(configRaw + ("folders" to JsonObject(
                    foldersRaw + ("Character" to JsonObject(characterRaw + ("futureFolder" to JsonPrimitive("keep")))),
                ))),
            ))
            fileManager.setPreferences(
                fileManager.bookmarks.value.copy(
                    fileData = PlatformFile(heroFile),
                    fileRelativePath = "Character/Hero.md",
                )
            )

            val renamed = fileManager.renameProjectFolder(
                folder = ProjectFolder(FolderKey.of("Character"), PlatformFile(characterDirectory)),
                newName = "Renamed Character",
                folderConfig = folderConfig,
            )

            assertNotNull(renamed)
            assertTrue(File(projectDirectory, "Renamed Character/Hero.md").isFile)
            assertTrue(!characterDirectory.exists())
            assertNull(fileManager.projectConfig.value?.folders?.get("Character"))
            assertEquals(folderConfig, fileManager.projectConfig.value?.folders?.get("Renamed Character"))
            val renamedRaw = Json.parseToJsonElement(configFile.readText()).jsonObject
            assertEquals(
                "keep",
                renamedRaw.getValue("folders").jsonObject.getValue("Renamed Character").jsonObject
                    .getValue("futureFolder").jsonPrimitive.content,
            )
            assertEquals(
                "Renamed Character/Hero.md",
                fileManager.projectConfig.value?.fileIds?.get("hero-id"),
            )
            assertEquals("Renamed Character/Hero.md", fileManager.bookmarks.value.fileRelativePath)
            assertEquals("Renamed Character/Hero.md", renamed.selectedFileKey?.relativePath)

            val beforeExternalRename = Json.parseToJsonElement(configFile.readText()).jsonObject
            val externalRaw = Json.encodeToString(
                JsonElement.serializer(),
                JsonObject(
                    beforeExternalRename + ("propertyTypes" to JsonObject(
                        beforeExternalRename.getValue("propertyTypes").jsonObject +
                            ("external" to JsonPrimitive("text")),
                    )),
                ),
            )
            configFile.writeText(externalRaw)
            assertNull(
                fileManager.renameProjectFolder(
                    folder = renamed.projectFolder,
                    newName = "Another Name",
                    folderConfig = folderConfig,
                ),
            )
            assertEquals(externalRaw, configFile.readText())
            assertTrue(File(projectDirectory, "Renamed Character/Hero.md").isFile)
            assertFalse(File(projectDirectory, "Another Name").exists())
            assertEquals(DocumentPropertyType.TEXT, fileManager.projectConfig.value?.propertyTypes?.get("external"))

            File(projectDirectory, "Existing").mkdirs()
            assertNull(
                fileManager.renameProjectFolder(
                    folder = renamed.projectFolder,
                    newName = "Existing",
                    folderConfig = folderConfig,
                )
            )
            assertTrue(File(projectDirectory, "Renamed Character/Hero.md").isFile)
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
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
            fileManager.openProjectThroughWorkspaceFlow(
                vault = testRoot,
                project = projectDirectory,
            )
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

    private suspend fun FileManager.openProjectThroughWorkspaceFlow(
        vault: File,
        project: File,
        expectConfirmation: Boolean = true,
    ) {
        setPreferences(Bookmarks(vaultData = PlatformFile(vault)))

        requestOpenWorkspace(PlatformFile(project))

        if (expectConfirmation) {
            val request = assertNotNull(workspaceOpenRequest.value)
            assertEquals(PlatformFile(project).toString(), request.directory.toString())
            confirmWorkspaceOpen(WorkspaceKind.PROJECT)
        } else {
            assertNull(
                workspaceOpenRequest.value,
                "a project with a valid legacy config must activate without confirmation",
            )
        }
        assertNull(workspaceOpenRequest.value)
        assertEquals(PlatformFile(project).toString(), bookmarks.value.projectData?.toString())
        assertEquals(WorkspaceKind.PROJECT, bookmarks.value.workspaceKind)
    }
}
