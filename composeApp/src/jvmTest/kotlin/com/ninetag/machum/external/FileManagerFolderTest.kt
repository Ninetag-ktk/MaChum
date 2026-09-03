package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.ProjectConfig
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileManagerFolderTest {

    @Test
    fun discoversVisibleProjectFoldersAndBuildsRelativeFileKeys() = withFileManager { fileManager, project ->
        File(project, "root.md").writeText("root")
        File(project, ".hidden").apply { mkdirs() }
        File(project, ".hidden/secret.md").writeText("secret")
        File(project, "Character").apply { mkdirs() }
        File(project, "Character/same.md").writeText("character")
        File(project, "Scene/Act1").apply { mkdirs() }
        File(project, "Scene/same.md").writeText("scene")
        File(project, "Scene/Act1/intro.MD").writeText("intro")

        val folders = fileManager.listFolders(PlatformFile(project))
        assertEquals(
            listOf("", "Character", "Scene"),
            folders.map { it.key.relativePath },
        )

        val character = folders.first { it.key == FolderKey.of("Character") }
        val scene = folders.first { it.key == FolderKey.of("Scene") }
        assertEquals(listOf("Character/same.md"), fileManager.listProjectFiles(character).map { it.key.relativePath })
        assertEquals(listOf("Scene/same.md"), fileManager.listProjectFiles(scene).map { it.key.relativePath })
    }

    @Test
    fun projectFolderFileBookmarkRestoresByRelativePath() = withFileManager { fileManager, project ->
        val scene = File(project, "Scene").apply { mkdirs() }
        val note = File(scene, "same.md").apply { writeText("scene") }
        val projectFile = ProjectFile(FileKey.of("Scene/same.md"), PlatformFile(note))

        fileManager.setPreferences(
            Bookmarks(
                projectData = PlatformFile(project),
                fileData = projectFile.platformFile,
                fileRelativePath = projectFile.key.relativePath,
            )
        )

        val restored = fileManager.getPreferences()
        assertEquals("Scene/same.md", restored.fileRelativePath)
        assertNotNull(restored.fileData)
        assertEquals(projectFile.platformFile.toString(), restored.fileData.toString())
    }

    @Test
    fun projectFolderFileRenameUsesItsActualParentFolder() = withFileManager { fileManager, project ->
        val scene = File(project, "Scene").apply { mkdirs() }
        val note = File(scene, "old.md").apply { writeText("scene") }
        val projectFile = ProjectFile(FileKey.of("Scene/old.md"), PlatformFile(note))
        fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))

        val renamed = fileManager.renameFile(projectFile, "new")

        assertNotNull(renamed)
        assertEquals("scene", File(scene, "new.md").readText())
        assertNull(File(project, "new.md").takeIf { it.exists() })
    }

    @Test
    fun projectFileRenameRejectsInvalidAndDuplicateNamesWithoutAutoSuffix() = withFileManager { fileManager, project ->
        val scene = File(project, "Scene").apply { mkdirs() }
        val old = File(scene, "old.md").apply { writeText("old") }
        File(scene, "existing.md").writeText("existing")
        val projectFile = ProjectFile(FileKey.of("Scene/old.md"), PlatformFile(old))
        fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))

        assertNull(fileManager.renameFile(projectFile, "CON"))
        assertNull(fileManager.renameFile(projectFile, "existing"))
        assertTrue(old.exists())
        assertTrue(!File(scene, "existing 1.md").exists())
    }

    @Test
    fun applyDefaultOrderSwapsNumberedFilesAndUpdatesIdentityAndBookmarkPaths() =
        withFileManager { fileManager, project ->
            val concept = File(project, "Concept").apply { mkdirs() }
            val firstBody = "---\nid: first-id\n---\n\nfirst"
            val secondBody = "---\nid: second-id\n---\n\nsecond"
            val first = File(concept, "1. Same.md").apply { writeText(firstBody) }
            File(concept, "2. Same.md").writeText(secondBody)
            File(concept, "Memo.md").writeText("memo")
            fileManager.setPreferences(
                Bookmarks(
                    projectData = PlatformFile(project),
                    fileData = PlatformFile(first),
                    fileRelativePath = "Concept/1. Same.md",
                ),
            )
            fileManager.writeConfig(
                ProjectConfig(
                    fileIds = mapOf(
                        "first-id" to "Concept/1. Same.md",
                        "second-id" to "Concept/2. Same.md",
                        "memo-id" to "Concept/Memo.md",
                    ),
                ),
            )
            val folder = ProjectFolder(FolderKey.of("Concept"), PlatformFile(concept))
            val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }

            val result = fileManager.applyDefaultOrder(
                folder = folder,
                orderedFileKeys = listOf(
                    files.getValue("2. Same.md").key,
                    files.getValue("1. Same.md").key,
                ),
            )

            assertNotNull(result)
            assertEquals(secondBody, File(concept, "1. Same.md").readText())
            assertEquals(firstBody, File(concept, "2. Same.md").readText())
            assertEquals("memo", File(concept, "Memo.md").readText())
            assertEquals(
                "Concept/2. Same.md",
                fileManager.projectConfig.value?.fileIds?.get("first-id"),
            )
            assertEquals(
                "Concept/1. Same.md",
                fileManager.projectConfig.value?.fileIds?.get("second-id"),
            )
            assertEquals("Concept/Memo.md", fileManager.projectConfig.value?.fileIds?.get("memo-id"))
            assertEquals("Concept/2. Same.md", fileManager.bookmarks.value.fileRelativePath)
            assertEquals(File(concept, "2. Same.md").absolutePath, fileManager.bookmarks.value.fileData?.file?.absolutePath)
            assertFalse(concept.listFiles().orEmpty().any { it.name.startsWith(".machum-order-") })
        }

    @Test
    fun applyDefaultOrderUsesZeroForRootAndRejectsIncompleteOrDuplicateInput() =
        withFileManager { fileManager, project ->
            File(project, "4. Four.md").writeText("four")
            File(project, "7. Seven.md").writeText("seven")
            File(project, "Memo.md").writeText("memo")
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
            assertNotNull(fileManager.writeConfig(ProjectConfig()))
            val folder = ProjectFolder(FolderKey.Base, PlatformFile(project))
            val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }
            val four = files.getValue("4. Four.md").key
            val seven = files.getValue("7. Seven.md").key

            assertNull(fileManager.applyDefaultOrder(folder, listOf(four)))
            assertNull(fileManager.applyDefaultOrder(folder, listOf(four, four)))
            assertTrue(File(project, "4. Four.md").isFile)
            assertTrue(File(project, "7. Seven.md").isFile)

            assertNotNull(fileManager.applyDefaultOrder(folder, listOf(seven, four)))
            assertEquals("seven", File(project, "0. Seven.md").readText())
            assertEquals("four", File(project, "1. Four.md").readText())
            assertEquals("memo", File(project, "Memo.md").readText())
        }

    @Test
    fun applyDefaultOrderRejectsBeforeMutationWhenProjectConfigIsNotLoaded() =
        withFileManager { fileManager, project ->
            val concept = File(project, "Concept").apply { mkdirs() }
            File(concept, "1. Alpha.md").writeText("alpha")
            File(concept, "2. Beta.md").writeText("beta")
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
            val folder = ProjectFolder(FolderKey.of("Concept"), PlatformFile(concept))
            val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }

            assertNull(
                fileManager.applyDefaultOrder(
                    folder,
                    listOf(files.getValue("2. Beta.md").key, files.getValue("1. Alpha.md").key),
                ),
            )
            assertEquals("alpha", File(concept, "1. Alpha.md").readText())
            assertEquals("beta", File(concept, "2. Beta.md").readText())
            assertFalse(concept.listFiles().orEmpty().any { it.name.startsWith(".machum-order-") })
        }

    @Test
    fun applyDefaultOrderRollsBackWhenASecondTemporaryNameCollides() =
        withFileManager { fileManager, project ->
            val concept = File(project, "Concept").apply { mkdirs() }
            File(concept, "1. Alpha.md").writeText("alpha")
            File(concept, "2. Beta.md").writeText("beta")
            File(concept, ".machum-order-default-1.md").writeText("collision")
            File(concept, ".machum-order-rollback-0.md").writeText("reserved rollback")
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
            assertNotNull(fileManager.writeConfig(ProjectConfig()))
            val folder = ProjectFolder(FolderKey.of("Concept"), PlatformFile(concept))
            val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }

            val result = fileManager.applyDefaultOrder(
                folder,
                listOf(files.getValue("2. Beta.md").key, files.getValue("1. Alpha.md").key),
            )

            assertNull(result)
            assertEquals("alpha", File(concept, "1. Alpha.md").readText())
            assertEquals("beta", File(concept, "2. Beta.md").readText())
            assertEquals("collision", File(concept, ".machum-order-default-1.md").readText())
            assertEquals("reserved rollback", File(concept, ".machum-order-rollback-0.md").readText())
            assertEquals(
                listOf(".machum-order-rollback-0.md"),
                concept.listFiles().orEmpty()
                    .map(File::getName)
                    .filter { name -> name.startsWith(".machum-order-rollback-") },
            )
            assertFalse(File(concept, ".machum-order-default-0.md").exists())
        }

    @Test
    fun applyDefaultOrderRollsBackInNonCancellableContextWhenCallerIsCancelled() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-order-cancel").toFile()
        val project = File(testRoot, "Project").apply { mkdirs() }
        val concept = File(project, "Concept").apply { mkdirs() }
        val firstBody = "---\nid: first-id\n---\n\nfirst"
        val secondBody = "---\nid: second-id\n---\n\nsecond"
        val first = File(concept, "1. Same.md").apply { writeText(firstBody) }
        File(concept, "2. Same.md").writeText(secondBody)
        val dataStore = InterruptiblePreferencesDataStore()

        try {
            val fileManager = FileManager(dataStore)
            val originalBookmarks = Bookmarks(
                projectData = PlatformFile(project),
                fileData = PlatformFile(first),
                fileRelativePath = "Concept/1. Same.md",
            )
            fileManager.setPreferences(originalBookmarks)
            assertNotNull(
                fileManager.writeConfig(
                    ProjectConfig(
                        fileIds = mapOf(
                            "first-id" to "Concept/1. Same.md",
                            "second-id" to "Concept/2. Same.md",
                        ),
                    ),
                ),
            )
            val folder = ProjectFolder(FolderKey.of("Concept"), PlatformFile(concept))
            val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }
            val updateBlocked = dataStore.arm(InterruptBehavior.AWAIT_CANCELLATION)
            val operation = async(Dispatchers.Default) {
                fileManager.applyDefaultOrder(
                    folder,
                    listOf(files.getValue("2. Same.md").key, files.getValue("1. Same.md").key),
                )
            }

            withTimeout(5_000.milliseconds) { updateBlocked.await() }
            operation.cancel(CancellationException("cancel order transaction"))
            assertFailsWith<CancellationException> { operation.await() }

            assertEquals(firstBody, File(concept, "1. Same.md").readText())
            assertEquals(secondBody, File(concept, "2. Same.md").readText())
            assertEquals(
                "Concept/1. Same.md",
                fileManager.projectConfig.value?.fileIds?.get("first-id"),
            )
            assertEquals("Concept/1. Same.md", fileManager.bookmarks.value.fileRelativePath)
            assertEquals("Concept/1. Same.md", fileManager.getPreferences().fileRelativePath)
            assertFalse(concept.listFiles().orEmpty().any { it.name.startsWith(".machum-order-") })
        } finally {
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun applyDefaultOrderRethrowsInjectedCancellationAfterRollback() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-order-cancellation-exception").toFile()
        val project = File(testRoot, "Project").apply { mkdirs() }
        val concept = File(project, "Concept").apply { mkdirs() }
        File(concept, "1. Alpha.md").writeText("alpha")
        File(concept, "2. Beta.md").writeText("beta")
        val dataStore = InterruptiblePreferencesDataStore()

        try {
            val fileManager = FileManager(dataStore)
            val first = PlatformFile(File(concept, "1. Alpha.md"))
            fileManager.setPreferences(
                Bookmarks(
                    projectData = PlatformFile(project),
                    fileData = first,
                    fileRelativePath = "Concept/1. Alpha.md",
                ),
            )
            assertNotNull(fileManager.writeConfig(ProjectConfig()))
            val folder = ProjectFolder(FolderKey.of("Concept"), PlatformFile(concept))
            val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }
            dataStore.arm(InterruptBehavior.THROW_CANCELLATION)

            val error = assertFailsWith<CancellationException> {
                fileManager.applyDefaultOrder(
                    folder,
                    listOf(files.getValue("2. Beta.md").key, files.getValue("1. Alpha.md").key),
                )
            }

            assertEquals("injected order cancellation", error.message)
            assertEquals("alpha", File(concept, "1. Alpha.md").readText())
            assertEquals("beta", File(concept, "2. Beta.md").readText())
            assertEquals("Concept/1. Alpha.md", fileManager.bookmarks.value.fileRelativePath)
            assertFalse(concept.listFiles().orEmpty().any { it.name.startsWith(".machum-order-") })
        } finally {
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun applyPlotOrderRenamesExactlyAndWritesStageFrontmatter() = withFileManager { fileManager, project ->
        val scene = File(project, "Scene").apply { mkdirs() }
        val opening = File(scene, "0. 시작.md").apply {
            writeText("---\nid: opening-id\nplot: 1) 발단\n---\n\n시작")
        }
        File(scene, "메모.md").writeText("메모")
        fileManager.setPreferences(
            Bookmarks(
                projectData = PlatformFile(project),
                fileData = PlatformFile(opening),
                fileRelativePath = "Scene/0. 시작.md",
            ),
        )
        fileManager.writeConfig(
            ProjectConfig(fileIds = mapOf("opening-id" to "Scene/0. 시작.md")),
        )
        val folder = ProjectFolder(FolderKey.of("Scene"), PlatformFile(scene))
        val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }

        val result = fileManager.applyPlotOrder(
            folder = folder,
            assignments = listOf(
                PlotOrderAssignment(files.getValue("0. 시작.md").key, PlotStage.SETUP, 2),
                PlotOrderAssignment(files.getValue("메모.md").key, PlotStage.DEVELOPMENT, 1),
            ),
        )

        assertNotNull(result)
        assertEquals(setOf("1-2. 시작.md", "2-1. 메모.md"), scene.listFiles()!!.filter { it.isFile }.map { it.name }.toSet())
        assertEquals(PlotStage.SETUP, NoteFile.parse(File(scene, "1-2. 시작.md").readText()).plotStage)
        assertEquals(PlotStage.DEVELOPMENT, NoteFile.parse(File(scene, "2-1. 메모.md").readText()).plotStage)
        assertEquals("Scene/1-2. 시작.md", fileManager.projectConfig.value?.fileIds?.get("opening-id"))
        assertEquals("Scene/1-2. 시작.md", fileManager.bookmarks.value.fileRelativePath)
    }

    @Test
    fun applyPlotOrderRejectsZeroNegativeAndDuplicateStageOrdersWithoutMutation() =
        withFileManager { fileManager, project ->
            val scene = File(project, "Scene").apply { mkdirs() }
            File(scene, "1-0. A.md").writeText("---\nplot: 1) 발단\n---\n\na")
            File(scene, "1-1. B.md").writeText("---\nplot: 1) 발단\n---\n\nb")
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
            assertNotNull(fileManager.writeConfig(ProjectConfig()))
            val folder = ProjectFolder(FolderKey.of("Scene"), PlatformFile(scene))
            val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }
            val a = files.getValue("1-0. A.md").key
            val b = files.getValue("1-1. B.md").key

            assertNull(
                fileManager.applyPlotOrder(
                    folder,
                    listOf(
                        PlotOrderAssignment(a, PlotStage.SETUP, 1),
                        PlotOrderAssignment(b, PlotStage.SETUP, 1),
                    ),
                ),
            )
            assertNull(
                fileManager.applyPlotOrder(
                    folder,
                    listOf(PlotOrderAssignment(a, PlotStage.SETUP, 1)),
                ),
                "a classified Plot file cannot be omitted from a partial assignment set",
            )
            assertNull(
                fileManager.applyPlotOrder(
                    folder,
                    listOf(
                        PlotOrderAssignment(a, PlotStage.SETUP, 0),
                        PlotOrderAssignment(b, PlotStage.SETUP, 2),
                    ),
                ),
                "Plot write assignments must be one-based",
            )
            assertNull(
                fileManager.applyPlotOrder(
                    folder,
                    listOf(
                        PlotOrderAssignment(a, PlotStage.SETUP, -1),
                        PlotOrderAssignment(b, PlotStage.SETUP, 1),
                    ),
                ),
            )
            assertTrue(File(scene, "1-0. A.md").isFile)
            assertTrue(File(scene, "1-1. B.md").isFile)
            assertFalse(scene.listFiles().orEmpty().any { it.name.startsWith(".machum-order-") })
        }

    private fun withFileManager(
        block: suspend (FileManager, File) -> Unit,
    ) = runBlocking {
        val testRoot = Files.createTempDirectory("machum-folders").toFile()
        val project = File(testRoot, "Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            block(FileManager(dataStore), project)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }
}

private enum class InterruptBehavior {
    AWAIT_CANCELLATION,
    THROW_CANCELLATION,
}

private class InterruptiblePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    private val nextBehavior = AtomicReference<InterruptBehavior?>(null)
    private val nextSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

    override val data: Flow<Preferences> = state

    fun arm(behavior: InterruptBehavior): CompletableDeferred<Unit> {
        check(nextBehavior.compareAndSet(null, behavior))
        return CompletableDeferred<Unit>().also { signal -> nextSignal.set(signal) }
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        when (nextBehavior.getAndSet(null)) {
            InterruptBehavior.AWAIT_CANCELLATION -> {
                nextSignal.getAndSet(null)?.complete(Unit)
                awaitCancellation()
            }
            InterruptBehavior.THROW_CANCELLATION -> {
                nextSignal.getAndSet(null)?.complete(Unit)
                throw CancellationException("injected order cancellation")
            }
            null -> Unit
        }
        return updated
    }
}
