package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.screen.mainScreen.MainViewModel
import com.ninetag.machum.screen.mainScreen.WorkspaceSaveCoordinator
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FileManagerAutoTagSyncTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun changedAutoTagSettingsRepairMissingIdWhenTagsAlreadyMatch() {
        val tags = listOf("Project", "수동", "신규")
        val previous = ProjectConfig(folders = mapOf(BASE_FOLDER_PATH to FolderConfig(autoTags = listOf("기존"))))
        val updatedFolderConfig = FolderConfig(autoTags = listOf("신규"))

        withAutoTagViewModel(
            initialConfig = previous,
            createFiles = { project ->
                listOf(File(project, "Note.md").apply { writeText(noteWithTags(*tags.toTypedArray())) })
            },
        ) { fixture, files ->
            val note = files.single()
            assertNull(NoteFile.parse(note.readText()).id)

            fixture.updateDirectoryAndFlush(FolderKey.Base, "", updatedFolderConfig)

            val persisted = NoteFile.parse(note.readText())
            assertNotNull(persisted.id)
            assertEquals(tags, persisted.tags)
            assertEquals("본문", persisted.body)
            assertEquals(
                updatedFolderConfig,
                fixture.fileManager.projectConfig.value?.folders?.get(BASE_FOLDER_PATH),
            )

            assertTrue(note.setLastModified(1_700_000_000_000))
            val firstBytes = note.readBytes()
            val firstModified = note.lastModified()

            fixture.updateDirectoryAndFlush(FolderKey.Base, "", updatedFolderConfig)

            assertContentEquals(firstBytes, note.readBytes())
            assertEquals(firstModified, note.lastModified())
        }
    }

    @Test
    fun autoTagSyncDoesNotTouchGeneralFolderFiles() {
        val previous = ProjectConfig(folders = mapOf(BASE_FOLDER_PATH to FolderConfig(autoTags = listOf("기존"))))
        val updatedFolderConfig = FolderConfig(autoTags = listOf("신규"))

        withAutoTagViewModel(
            workspaceKind = WorkspaceKind.GENERAL,
            initialConfig = previous,
            createFiles = { project ->
                listOf(File(project, "Note.md").apply { writeText(noteWithTags("수동", "기존")) })
            },
        ) { fixture, files ->
            val note = files.single()
            assertFalse(File(note.parentFile, ".machum.json").exists())
            assertTrue(note.setLastModified(1_700_000_000_000))
            val originalBytes = note.readBytes()
            val originalModified = note.lastModified()

            fixture.updateDirectoryAndFlush(FolderKey.Base, "", updatedFolderConfig)

            assertContentEquals(originalBytes, note.readBytes())
            assertEquals(originalModified, note.lastModified())
            val persisted = NoteFile.parse(note.readText())
            assertNull(persisted.id)
            assertEquals(listOf("수동", "기존"), persisted.tags)
            assertFalse(File(note.parentFile, ".machum.json").exists())
        }
    }

    @Test
    fun baseAndFolderTagChangesUpdateExistingFilesWithoutRenamingThem() {
        val initialConfig = ProjectConfig(
            folders = mapOf(
                BASE_FOLDER_PATH to FolderConfig(autoTags = listOf("기존-base")),
                "Character" to FolderConfig(autoTags = listOf("기존-folder")),
            ),
        )

        withAutoTagViewModel(
            initialConfig = initialConfig,
            createFiles = { project ->
                val character = File(project, "Character").apply { mkdirs() }
                listOf(
                    File(project, "0. Root.md").apply {
                        writeText(noteWithTags("수동-root", "기존-base"))
                    },
                    File(character, "Hero.md").apply {
                        writeText(noteWithTags("수동-character", "기존-base", "기존-folder"))
                    },
                )
            },
        ) { fixture, files ->
            val (rootFile, characterFile) = files
            fixture.updateDirectoryAndFlush(
                folderKey = FolderKey.Base,
                updatedName = "",
                folderConfig = FolderConfig(autoTags = listOf("신규-base")),
            )

            assertEquals(
                listOf("Project", "수동-root", "신규-base"),
                fixture.fileManager.readMarkdown(PlatformFile(rootFile)).tags,
            )
            assertEquals(
                listOf("Project", "수동-character", "신규-base", "기존-folder"),
                fixture.fileManager.readMarkdown(PlatformFile(characterFile)).tags,
            )

            fixture.updateDirectoryAndFlush(
                folderKey = FolderKey.of("Character"),
                updatedName = "Character",
                folderConfig = FolderConfig(autoTags = listOf("신규-folder")),
            )

            assertEquals(
                listOf("Project", "수동-root", "신규-base"),
                fixture.fileManager.readMarkdown(PlatformFile(rootFile)).tags,
            )
            assertEquals(
                listOf("Project", "수동-character", "신규-base", "신규-folder"),
                fixture.fileManager.readMarkdown(PlatformFile(characterFile)).tags,
            )
            assertEquals("0. Root.md", rootFile.name)
            assertEquals("Hero.md", characterFile.name)
        }
    }

    private fun withAutoTagViewModel(
        workspaceKind: WorkspaceKind = WorkspaceKind.PROJECT,
        initialConfig: ProjectConfig,
        createFiles: (File) -> List<File>,
        block: suspend (AutoTagViewModelFixture, List<File>) -> Unit,
    ) = runBlocking {
        val testRoot = Files.createTempDirectory("machum-view-model-auto-tag").toFile()
        val project = File(testRoot, "Project").apply { mkdir() }
        val files = createFiles(project)
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }
        var viewModel: MainViewModel? = null
        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project), workspaceKind = workspaceKind))
            assertNotNull(fileManager.writeConfig(initialConfig))
            if (workspaceKind == WorkspaceKind.GENERAL) {
                assertFalse(File(project, ".machum.json").exists())
            }

            val workspaceSaveCoordinator = WorkspaceSaveCoordinator()
            val createdViewModel = MainViewModel(fileManager, workspaceSaveCoordinator)
            viewModel = createdViewModel
            val expectedLocations = files.map { PlatformFile(it).toString() }.toSet()
            withTimeout(5_000.milliseconds) {
                createdViewModel.hierarchyState
                    .filter { state ->
                        state.folderContents.values
                            .flatMap { content -> content.files }
                            .map { projectFile -> projectFile.platformFile.toString() }
                            .toSet() == expectedLocations
                    }
                    .first()
            }
            val viewModelJob = checkNotNull(createdViewModel.viewModelScope.coroutineContext[Job])
            block(
                AutoTagViewModelFixture(
                    fileManager = fileManager,
                    workspaceSaveCoordinator = workspaceSaveCoordinator,
                    viewModel = createdViewModel,
                    viewModelJob = viewModelJob,
                    initialJobs = viewModelJob.children.toSet(),
                ),
                files,
            )
        } finally {
            viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            testRoot.deleteRecursively()
        }
    }

    private data class AutoTagViewModelFixture(
        val fileManager: FileManager,
        val workspaceSaveCoordinator: WorkspaceSaveCoordinator,
        val viewModel: MainViewModel,
        val viewModelJob: Job,
        val initialJobs: Set<Job>,
    ) {
        suspend fun updateDirectoryAndFlush(
            folderKey: FolderKey,
            updatedName: String,
            folderConfig: FolderConfig,
        ) {
            viewModel.updateDirectory(folderKey, updatedName, folderConfig)
            val mutationJobs = viewModelJob.children.filterNot { it in initialJobs }.toList()
            assertTrue(mutationJobs.isNotEmpty(), "updateDirectory mutation job was not observed")
            withTimeout(5_000.milliseconds) {
                mutationJobs.joinAll()
                workspaceSaveCoordinator.flushPendingWrites().getOrThrow()
            }
            assertNull(viewModel.workspaceTransitionError.value)
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
