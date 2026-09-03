package com.ninetag.machum.screen.mainComposition

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.NoteFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class MainViewModelRenameTest {

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
    fun renameFile_movesLiveEditorStateAndPendingSaveToTheNewKey() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-view-model-rename").toFile()
        val projectDirectory = File(testRoot, "Test Project").apply { mkdirs() }
        val oldFile = File(projectDirectory, "Draft.md").apply {
            writeText("---\nid: draft-id\ntags:\n  - Test_Project\n---\n\ninitial body")
        }
        val newFile = File(projectDirectory, "Renamed.md")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(
                Bookmarks(
                    projectData = PlatformFile(projectDirectory),
                    fileData = PlatformFile(oldFile),
                    fileRelativePath = oldFile.name,
                ),
            )
            val workspaceSaveCoordinator = WorkspaceSaveCoordinator()
            val viewModel = MainViewModel(fileManager, workspaceSaveCoordinator)

            val initialProjectFile = withTimeout(5_000.milliseconds) {
                viewModel.fileList
                    .filter { files -> files.size == 1 }
                    .first()
                    .single()
            }
            val oldKey = initialProjectFile.key
            viewModel.loadPage(initialProjectFile)
            withTimeout(5_000.milliseconds) {
                viewModel.fileLoadStates
                    .filter { states -> states[oldKey] is FileLoadUiState.Loaded }
                    .first()
            }

            val editorSessionKey = viewModel.editorSessionKey(oldKey)
            viewModel.updateBody(oldKey, "pending body")
            val pendingNote = (viewModel.fileLoadStates.value.getValue(oldKey) as FileLoadUiState.Loaded).noteFile

            assertNull(viewModel.renameFile(initialProjectFile, "Renamed"))

            val newKey = FileKey.of("Renamed.md")
            assertEquals(listOf(newKey), viewModel.fileList.value.map { it.key })
            assertEquals(newKey, viewModel.fileList.value[viewModel.currentIndex.value].key)
            assertFalse(oldKey in viewModel.fileLoadStates.value)
            val renamedState = viewModel.fileLoadStates.value[newKey] as FileLoadUiState.Loaded
            assertSame(pendingNote, renamedState.noteFile)
            assertEquals(editorSessionKey, viewModel.editorSessionKey(newKey))
            assertEquals(newKey.relativePath, fileManager.bookmarks.value.fileRelativePath)
            assertEquals(newFile.absolutePath, fileManager.bookmarks.value.fileData?.file?.absolutePath)
            assertFalse(oldFile.exists())
            assertTrue(newFile.isFile)

            workspaceSaveCoordinator.flushPendingWrites().getOrThrow()

            assertEquals("pending body", NoteFile.parse(newFile.readText()).body)
            assertFalse(oldFile.exists(), "stale pending save must not recreate the old path")
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }
}
