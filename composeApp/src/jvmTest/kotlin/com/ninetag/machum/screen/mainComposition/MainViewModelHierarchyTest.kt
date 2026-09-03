package com.ninetag.machum.screen.mainComposition

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.defaultProjectConfig
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.PlotOrderAssignment
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelHierarchyTest {

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
    fun hierarchySnapshot_coversEveryFolderAndRefreshesCreateAndRenameInPlace() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-view-model-hierarchy").toFile()
        val projectDirectory = File(testRoot, "Test Project").apply { mkdirs() }
        val rootFile = File(projectDirectory, "0. Root.md").apply { writeText("root") }
        val conceptDirectory = File(projectDirectory, "1. Concept").apply { mkdirs() }
        File(conceptDirectory, "1. Premise.md").writeText("premise")
        val characterDirectory = File(projectDirectory, "3. Character").apply { mkdirs() }
        File(characterDirectory, "Hero.md").writeText("hero")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(
                Bookmarks(
                    projectData = PlatformFile(projectDirectory),
                    fileData = PlatformFile(rootFile),
                    fileRelativePath = rootFile.name,
                ),
            )
            val viewModel = MainViewModel(fileManager, WorkspaceSaveCoordinator())
            val rootKey = FolderKey.Base
            val conceptKey = FolderKey.of("1. Concept")
            val characterKey = FolderKey.of("3. Character")

            val initialContents = withTimeout(5_000.milliseconds) {
                viewModel.hierarchyFolderContents
                    .filter { contents -> contents.keys.containsAll(listOf(rootKey, conceptKey, characterKey)) }
                    .first()
            }

            assertEquals(
                listOf(FileKey.of("0. Root.md")),
                initialContents.getValue(rootKey).files.map { it.key },
            )
            assertEquals(
                listOf(FileKey.of("1. Concept/1. Premise.md")),
                initialContents.getValue(conceptKey).files.map { it.key },
            )
            assertEquals(
                listOf(FileKey.of("3. Character/Hero.md")),
                initialContents.getValue(characterKey).files.map { it.key },
            )

            val conceptKeysBefore = initialContents.getValue(conceptKey).files.map { it.key }
            val characterFileKey = FileKey.of("3. Character/Hero.md")
            viewModel.selectFile(characterFileKey)

            withTimeout(5_000.milliseconds) {
                viewModel.currentFolder
                    .filter { folder -> folder?.key == characterKey }
                    .first()
            }
            val selectedCharacterFile = withTimeout(5_000.milliseconds) {
                combine(viewModel.fileList, viewModel.currentIndex) { files, index ->
                    files.getOrNull(index)?.key
                }
                    .filter { key -> key == characterFileKey }
                    .first()
            }
            assertEquals(characterFileKey, selectedCharacterFile)
            assertEquals(
                conceptKeysBefore,
                viewModel.hierarchyFolderContents.value.getValue(conceptKey).files.map { it.key },
                "cross-folder selection must not discard another folder's hierarchy snapshot",
            )

            viewModel.createFileInCurrentFolder("Sidekick")
            val createdKey = FileKey.of("3. Character/1. Sidekick.md")
            val afterCreate = withTimeout(5_000.milliseconds) {
                viewModel.hierarchyFolderContents
                    .filter { contents ->
                        contents[characterKey]?.files?.any { it.key == createdKey } == true
                    }
                    .first()
            }
            val selectedAfterCreate = withTimeout(5_000.milliseconds) {
                combine(viewModel.fileList, viewModel.currentIndex) { files, index ->
                    files.getOrNull(index)?.key
                }
                    .filter { key -> key == createdKey }
                    .first()
            }
            assertEquals(createdKey, selectedAfterCreate)
            withTimeout(5_000.milliseconds) {
                fileManager.bookmarks
                    .filter { bookmarks -> bookmarks.fileRelativePath == createdKey.relativePath }
                    .first()
            }
            assertEquals(conceptKeysBefore, afterCreate.getValue(conceptKey).files.map { it.key })

            val createdFile = afterCreate.getValue(characterKey).files.first { it.key == createdKey }
            val editorSessionKey = viewModel.editorSessionKey(createdKey)
            assertNull(viewModel.renameFile(createdFile, "1. Ally"))

            val renamedKey = FileKey.of("3. Character/1. Ally.md")
            val afterRename = viewModel.hierarchyFolderContents.value
            val characterKeys = afterRename.getValue(characterKey).files.map { it.key }
            assertTrue(renamedKey in characterKeys)
            assertFalse(createdKey in characterKeys)
            assertEquals(renamedKey, viewModel.fileList.value[viewModel.currentIndex.value].key)
            assertEquals(editorSessionKey, viewModel.editorSessionKey(renamedKey))
            assertEquals(conceptKeysBefore, afterRename.getValue(conceptKey).files.map { it.key })
            assertFalse(File(characterDirectory, "1. Sidekick.md").exists())
            assertTrue(File(characterDirectory, "1. Ally.md").isFile)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun defaultProjectRootCreatesOneBasedPlotFilesWithStageMetadata() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-view-model-root-plot").toFile()
        val projectDirectory = File(testRoot, "Test Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(projectDirectory)))
            assertEquals(defaultProjectConfig(), fileManager.writeConfig(defaultProjectConfig()))
            val viewModel = MainViewModel(fileManager, WorkspaceSaveCoordinator())
            val rootKey = FolderKey.Base

            withTimeout(5_000.milliseconds) {
                viewModel.currentFolder
                    .filter { folder -> folder?.key == rootKey }
                    .first()
            }

            viewModel.createPlotFile(PlotStage.PROLOGUE, "Opening")
            val firstKey = FileKey.of("0-1. Opening.md")
            withTimeout(5_000.milliseconds) {
                viewModel.hierarchyFolderContents
                    .filter { contents ->
                        contents[rootKey]?.plotEntries?.any { entry ->
                            entry.projectFile.key == firstKey &&
                                entry.stage == PlotStage.PROLOGUE &&
                                entry.order == 1
                        } == true
                    }
                    .first()
            }

            viewModel.createPlotFile(PlotStage.PROLOGUE, "Continuation")
            val secondKey = FileKey.of("0-2. Continuation.md")
            val rootContent = withTimeout(5_000.milliseconds) {
                viewModel.hierarchyFolderContents
                    .filter { contents ->
                        contents[rootKey]?.plotEntries?.any { it.projectFile.key == secondKey } == true
                    }
                    .first()
                    .getValue(rootKey)
            }

            assertEquals(listOf(firstKey, secondKey), rootContent.files.map { it.key })
            assertEquals(
                PlotStage.PROLOGUE,
                NoteFile.parse(File(projectDirectory, secondKey.fileName).readText()).plotStage,
            )
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun plotHierarchyContent_loadsStageOrderAndKeepsUnclassifiedFilesLast() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-view-model-plot-hierarchy").toFile()
        val projectDirectory = File(testRoot, "Test Project").apply { mkdirs() }
        File(projectDirectory, "0. Root.md").writeText("root")
        val sceneDirectory = File(projectDirectory, "4. Scene").apply { mkdirs() }
        File(sceneDirectory, "1-2. Later Setup.md").writeText(plotNote("1) 발단", "later"))
        File(sceneDirectory, "2-1. Development.md").writeText(plotNote("2) 전개", "development"))
        File(sceneDirectory, "1-0. First Setup.md").writeText(plotNote("1) 발단", "first"))
        File(sceneDirectory, "0-0. Prologue.md").writeText(plotNote("0) 프롤로그", "prologue"))
        File(sceneDirectory, "Memo.md").writeText("unclassified")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(projectDirectory)))
            assertEquals(defaultProjectConfig(), fileManager.writeConfig(defaultProjectConfig()))
            val viewModel = MainViewModel(fileManager, WorkspaceSaveCoordinator())
            val sceneKey = FolderKey.of("4. Scene")

            val sceneContent = withTimeout(5_000.milliseconds) {
                viewModel.hierarchyFolderContents
                    .filter { contents -> contents[sceneKey]?.plotEntries?.size == 5 }
                    .first()
                    .getValue(sceneKey)
            }

            assertEquals(
                listOf(
                    FileKey.of("4. Scene/0-0. Prologue.md"),
                    FileKey.of("4. Scene/1-0. First Setup.md"),
                    FileKey.of("4. Scene/1-2. Later Setup.md"),
                    FileKey.of("4. Scene/2-1. Development.md"),
                    FileKey.of("4. Scene/Memo.md"),
                ),
                sceneContent.files.map { it.key },
            )
            assertEquals(
                listOf(
                    PlotStage.PROLOGUE,
                    PlotStage.SETUP,
                    PlotStage.SETUP,
                    PlotStage.DEVELOPMENT,
                    null,
                ),
                sceneContent.plotEntries.map { it.stage },
            )
            assertEquals(listOf(0, 0, 2, 1, null), sceneContent.plotEntries.map { it.order })
            assertEquals(
                sceneContent.files.map { it.key },
                sceneContent.plotEntries.map { it.projectFile.key },
                "the flattened hierarchy must receive files and plot metadata in the same stable order",
            )

            viewModel.selectFolder(sceneKey)
            withTimeout(5_000.milliseconds) {
                viewModel.currentFolder
                    .filter { folder -> folder?.key == sceneKey }
                    .first()
            }
            viewModel.createPlotFile(PlotStage.CRISIS, "Turning Point")

            val createdKey = FileKey.of("4. Scene/3-1. Turning Point.md")
            val afterCreate = withTimeout(5_000.milliseconds) {
                viewModel.hierarchyFolderContents
                    .filter { contents ->
                        contents[sceneKey]?.plotEntries?.any { entry ->
                            entry.projectFile.key == createdKey &&
                                entry.stage == PlotStage.CRISIS &&
                                entry.order == 1
                        } == true
                    }
                    .first()
                    .getValue(sceneKey)
            }
            assertEquals(
                listOf(
                    FileKey.of("4. Scene/0-0. Prologue.md"),
                    FileKey.of("4. Scene/1-0. First Setup.md"),
                    FileKey.of("4. Scene/1-2. Later Setup.md"),
                    FileKey.of("4. Scene/2-1. Development.md"),
                    createdKey,
                    FileKey.of("4. Scene/Memo.md"),
                ),
                afterCreate.files.map { it.key },
            )
            assertEquals(
                afterCreate.files.map { it.key },
                afterCreate.plotEntries.map { it.projectFile.key },
            )
            val selectedAfterCreate = withTimeout(5_000.milliseconds) {
                combine(viewModel.fileList, viewModel.currentIndex) { files, index ->
                    files.getOrNull(index)?.key
                }
                    .filter { key -> key == createdKey }
                    .first()
            }
            assertEquals(createdKey, selectedAfterCreate)
            withTimeout(5_000.milliseconds) {
                fileManager.bookmarks
                    .filter { bookmarks -> bookmarks.fileRelativePath == createdKey.relativePath }
                    .first()
            }
            assertTrue(File(sceneDirectory, "3-1. Turning Point.md").isFile)

            viewModel.selectFolder(FolderKey.Base)
            withTimeout(5_000.milliseconds) {
                viewModel.currentFolder
                    .filter { folder -> folder?.key == FolderKey.Base }
                    .first()
            }
            val rootSelectedKey = FileKey.of("0. Root.md")
            val selectedRootFile = withTimeout(5_000.milliseconds) {
                combine(viewModel.fileList, viewModel.currentIndex) { files, index ->
                    files.getOrNull(index)?.key
                }
                    .filter { key -> key == rootSelectedKey }
                    .first()
            }
            assertEquals(rootSelectedKey, selectedRootFile)
            val rootBookmark = withTimeout(5_000.milliseconds) {
                fileManager.bookmarks
                    .filter { bookmarks -> bookmarks.fileRelativePath == rootSelectedKey.relativePath }
                    .first()
                    .fileRelativePath
            }

            assertTrue(
                viewModel.savePlotOrder(
                    sceneKey,
                    listOf(
                        PlotOrderAssignment(
                            FileKey.of("4. Scene/0-0. Prologue.md"),
                            PlotStage.PROLOGUE,
                            1,
                        ),
                        PlotOrderAssignment(
                            FileKey.of("4. Scene/1-2. Later Setup.md"),
                            PlotStage.SETUP,
                            1,
                        ),
                        PlotOrderAssignment(
                            FileKey.of("4. Scene/1-0. First Setup.md"),
                            PlotStage.SETUP,
                            2,
                        ),
                        PlotOrderAssignment(
                            FileKey.of("4. Scene/2-1. Development.md"),
                            PlotStage.DEVELOPMENT,
                            1,
                        ),
                        PlotOrderAssignment(createdKey, PlotStage.CRISIS, 1),
                    ),
                ),
            )

            assertEquals(FolderKey.Base, viewModel.currentFolder.value?.key)
            assertEquals(listOf(rootSelectedKey), viewModel.fileList.value.map { it.key })
            assertEquals(rootBookmark, fileManager.bookmarks.value.fileRelativePath)
            assertEquals(
                listOf(rootSelectedKey),
                viewModel.plotFileEntries.value.map { it.projectFile.key },
            )
            assertNull(viewModel.plotFileEntries.value.single().stage)
            assertEquals(
                listOf(
                    FileKey.of("4. Scene/0-1. Prologue.md"),
                    FileKey.of("4. Scene/1-1. Later Setup.md"),
                    FileKey.of("4. Scene/1-2. First Setup.md"),
                    FileKey.of("4. Scene/2-1. Development.md"),
                    createdKey,
                    FileKey.of("4. Scene/Memo.md"),
                ),
                viewModel.hierarchyFolderContents.value.getValue(sceneKey).files.map { it.key },
            )
            viewModel.selectFolder(sceneKey)
            withTimeout(5_000.milliseconds) {
                viewModel.currentFolder
                    .filter { folder -> folder?.key == sceneKey }
                    .first()
            }
            val restoredSceneSelection = withTimeout(5_000.milliseconds) {
                combine(viewModel.fileList, viewModel.currentIndex) { files, index ->
                    files.getOrNull(index)?.key
                }
                    .filter { key -> key == createdKey }
                    .first()
            }
            assertEquals(createdKey, restoredSceneSelection)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun saveDefaultOrderPreservesCyclicEditorStateAndSelectedDocument() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-view-model-default-order").toFile()
        val projectDirectory = File(testRoot, "Test Project").apply { mkdirs() }
        val conceptDirectory = File(projectDirectory, "1. Concept").apply { mkdirs() }
        val firstFile = File(conceptDirectory, "1. Same.md").apply { writeText("first") }
        File(conceptDirectory, "2. Same.md").writeText("second")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(
                Bookmarks(
                    projectData = PlatformFile(projectDirectory),
                    fileData = PlatformFile(firstFile),
                    fileRelativePath = "1. Concept/1. Same.md",
                ),
            )
            fileManager.writeConfig(defaultProjectConfig())
            val viewModel = MainViewModel(fileManager, WorkspaceSaveCoordinator())
            val folderKey = FolderKey.of("1. Concept")
            val firstKey = FileKey.of("1. Concept/1. Same.md")
            val secondKey = FileKey.of("1. Concept/2. Same.md")
            val initialFiles = withTimeout(5_000.milliseconds) {
                viewModel.hierarchyFolderContents
                    .filter { contents -> contents[folderKey]?.files?.size == 2 }
                    .first()
                    .getValue(folderKey)
                    .files
            }.associateBy { it.key }
            withTimeout(5_000.milliseconds) {
                combine(
                    viewModel.currentFolder,
                    viewModel.fileList,
                    viewModel.currentIndex,
                ) { folder, files, index ->
                    folder?.key to files.getOrNull(index)?.key
                }
                    .filter { (currentFolderKey, selectedKey) ->
                        currentFolderKey == folderKey && selectedKey == firstKey
                    }
                    .first()
            }
            withTimeout(5_000.milliseconds) {
                fileManager.bookmarks
                    .filter { bookmarks -> bookmarks.fileRelativePath == firstKey.relativePath }
                    .first()
            }

            viewModel.loadPage(initialFiles.getValue(firstKey))
            withTimeout(5_000.milliseconds) {
                viewModel.fileLoadStates
                    .filter { states -> states[firstKey] is FileLoadUiState.Loaded }
                    .first()
            }
            viewModel.loadPage(initialFiles.getValue(secondKey))
            withTimeout(5_000.milliseconds) {
                viewModel.fileLoadStates
                    .filter { states -> states[secondKey] is FileLoadUiState.Loaded }
                    .first()
            }
            val firstSession = viewModel.editorSessionKey(firstKey)
            val secondSession = viewModel.editorSessionKey(secondKey)
            val secondNote = (viewModel.fileLoadStates.value.getValue(secondKey) as FileLoadUiState.Loaded).noteFile
            viewModel.updateBody(firstKey, "pending first")
            val pendingFirstNote =
                (viewModel.fileLoadStates.value.getValue(firstKey) as FileLoadUiState.Loaded).noteFile

            assertTrue(viewModel.saveDefaultOrder(folderKey, listOf(secondKey, firstKey)))

            assertEquals(secondKey, viewModel.fileList.value[viewModel.currentIndex.value].key)
            assertEquals(secondKey.relativePath, fileManager.bookmarks.value.fileRelativePath)
            assertEquals(secondSession, viewModel.editorSessionKey(firstKey))
            assertEquals(firstSession, viewModel.editorSessionKey(secondKey))
            assertSame(
                secondNote,
                (viewModel.fileLoadStates.value.getValue(firstKey) as FileLoadUiState.Loaded).noteFile,
            )
            assertSame(
                pendingFirstNote,
                (viewModel.fileLoadStates.value.getValue(secondKey) as FileLoadUiState.Loaded).noteFile,
            )
            assertEquals("second", NoteFile.parse(File(conceptDirectory, "1. Same.md").readText()).body)
            assertEquals("pending first", NoteFile.parse(File(conceptDirectory, "2. Same.md").readText()).body)
            assertFalse(conceptDirectory.listFiles().orEmpty().any { it.name.startsWith(".machum-order-") })
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    private fun plotNote(plot: String, body: String): String = """
        ---
        plot: $plot
        ---

        $body
    """.trimIndent()
}
