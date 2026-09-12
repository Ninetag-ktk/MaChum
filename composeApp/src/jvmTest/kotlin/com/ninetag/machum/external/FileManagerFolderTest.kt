package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun renameProjectFolderBookmarkFailureRollsBackDirectoryConfigAndSelection() =
        withFolderRenameFixture { fixture ->
            val failureReached = fixture.dataStore.arm(InterruptBehavior.THROW_FAILURE)

            val result = fixture.renameFolder()

            assertNull(result)
            assertTrue(failureReached.isCompleted)
            fixture.assertFullyRolledBack()
            assertNotNull(fixture.renameFolder(), "fully rolled back rename must be retryable")
            assertTrue(fixture.renamedHero.isFile)
            assertContentEquals(fixture.heroBytes, fixture.renamedHero.readBytes())
        }

    @Test
    fun generalFolderRenameFailureRollsBackWithoutCreatingProjectConfig() =
        withFolderRenameFixture(workspaceKind = WorkspaceKind.GENERAL) { fixture ->
            fixture.dataStore.arm(InterruptBehavior.THROW_FAILURE)

            assertNull(fixture.renameFolder())

            fixture.assertFullyRolledBack()
            assertFalse(fixture.configFile.exists())
        }

    @Test
    fun renameProjectFolderCallerCancellationAtBookmarkSaveRollsBackCompletely() =
        withFolderRenameFixture { fixture ->
            val bookmarkSaveStarted = fixture.dataStore.arm(InterruptBehavior.AWAIT_CANCELLATION)
            val operation = async(Dispatchers.Default) { fixture.renameFolder() }

            try {
                withTimeout(5_000.milliseconds) { bookmarkSaveStarted.await() }
                assertTrue(fixture.renamedDirectory.isDirectory)
                assertFalse(fixture.originalDirectory.exists())

                operation.cancel(CancellationException("cancel folder rename"))
                val error = assertFailsWith<CancellationException> { operation.await() }

                assertEquals("cancel folder rename", error.message)
                fixture.assertFullyRolledBack()
            } finally {
                operation.cancelAndJoin()
            }
        }

    @Test
    fun renameProjectFolderInjectedCancellationRollsBackAndRethrows() =
        withFolderRenameFixture { fixture ->
            fixture.dataStore.arm(InterruptBehavior.THROW_CANCELLATION)

            val error = assertFailsWith<CancellationException> { fixture.renameFolder() }

            assertEquals("injected order cancellation", error.message)
            fixture.assertFullyRolledBack()
        }

    @Test
    fun renameProjectFolderCancellationReportsRollbackFailureWhenOriginalPathIsOccupied() =
        withFolderRenameFixture { fixture ->
            val bookmarkSaveStarted = fixture.dataStore.arm(InterruptBehavior.AWAIT_CANCELLATION)
            val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val operation = operationScope.async { fixture.renameFolder() }

            try {
                withTimeout(5_000.milliseconds) { bookmarkSaveStarted.await() }
                val collision = fixture.originalDirectory.apply { mkdirs() }
                val sentinel = File(collision, "do-not-delete.txt").apply { writeText("collision") }

                operation.cancel(CancellationException("cancel with blocked rollback"))
                val error = assertFailsWith<FolderRenameRollbackException> { operation.await() }

                assertTrue(error.message.orEmpty().contains("완전히 되돌리지 못했습니다"))
                assertTrue(error.message.orEmpty().contains("추가 편집을 멈추고"))
                assertTrue(error.cause is CancellationException)
                assertEquals("cancel with blocked rollback", error.cause?.message)
                assertEquals(1, error.suppressedExceptions.size)
                assertTrue(
                    error.suppressedExceptions.single().message.orEmpty()
                        .contains("original directory restore failed"),
                )
                assertTrue(sentinel.isFile)
                assertEquals("collision", sentinel.readText())
                assertTrue(fixture.renamedHero.isFile)
                assertContentEquals(fixture.heroBytes, fixture.renamedHero.readBytes())
                assertEquals(fixture.heroLastModified, fixture.renamedHero.lastModified())
                assertContentEquals(fixture.configBytes, fixture.configFile.readBytes())
                assertEquals(fixture.originalConfig, fixture.fileManager.projectConfig.value)
                assertEquals(fixture.originalBookmarks, fixture.fileManager.bookmarks.value)
                assertEquals(
                    fixture.originalBookmarks.fileRelativePath,
                    fixture.fileManager.getPreferences().fileRelativePath,
                )
            } finally {
                operation.cancelAndJoin()
                operationScope.cancel()
            }
        }

    @Test
    fun renameProjectFolderDoesNotRewriteBookmarkSelectedInAnotherFolder() =
        withFolderRenameFixture(selectedInsideFolder = false) { fixture ->
            val unexpectedBookmarkSave = fixture.dataStore.arm(InterruptBehavior.THROW_FAILURE)

            val result = fixture.renameFolder()

            assertNotNull(result)
            assertFalse(unexpectedBookmarkSave.isCompleted)
            val renamedFile = result.filesByPreviousKey.getValue(FileKey.of("Character/Hero.md"))
            assertEquals(FileKey.of("Renamed Character/Hero.md"), renamedFile.key)
            assertEquals(PlatformFile(fixture.renamedHero).toString(), renamedFile.platformFile.toString())
            assertFalse(fixture.originalDirectory.exists())
            assertTrue(fixture.renamedHero.isFile)
            assertContentEquals(fixture.heroBytes, fixture.renamedHero.readBytes())
            assertEquals(fixture.heroLastModified, fixture.renamedHero.lastModified())
            assertEquals(
                "Renamed Character/Hero.md",
                fileManagerConfig(fixture).fileIds["hero-id"],
            )
            assertEquals("Scene/Other.md", fileManagerConfig(fixture).fileIds["other-id"])
            assertEquals(fixture.originalBookmarks, fixture.fileManager.bookmarks.value)
            assertEquals(
                fixture.originalBookmarks.fileRelativePath,
                fixture.fileManager.getPreferences().fileRelativePath,
            )
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
    fun applyPlotOrderCancellationRestoresOriginalMarkdownBytesAndMetadata() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-plot-raw-rollback").toFile()
        val project = File(testRoot, "Project").apply { mkdirs() }
        val scene = File(project, "Scene").apply { mkdirs() }
        val first = File(scene, "1-1. First.md").apply {
            writeText("\uFEFF---\r\nid: stable-id\r\ntags: [Project, manual]\r\nplot: 1) 발단\r\n# keep\r\ncustom: yes\r\n---\r\nfirst")
        }
        val second = File(scene, "Memo.md").apply { writeText("\uFEFFraw memo\r\nsecond line") }
        val firstBytes = first.readBytes()
        val secondBytes = second.readBytes()
        val dataStore = InterruptiblePreferencesDataStore()

        try {
            val fileManager = FileManager(dataStore)
            val bookmarks = Bookmarks(
                projectData = PlatformFile(project),
                fileData = PlatformFile(first),
                fileRelativePath = "Scene/1-1. First.md",
            )
            fileManager.setPreferences(bookmarks)
            val config = ProjectConfig(fileIds = mapOf("stable-id" to "Scene/1-1. First.md"))
            assertNotNull(fileManager.writeConfig(config))
            val configBytes = File(project, ".machum.json").readBytes()
            val folder = ProjectFolder(FolderKey.of("Scene"), PlatformFile(scene))
            dataStore.arm(InterruptBehavior.THROW_CANCELLATION)

            assertFailsWith<CancellationException> {
                fileManager.applyPlotOrder(
                    folder,
                    listOf(
                        PlotOrderAssignment(FileKey.of("Scene/1-1. First.md"), PlotStage.DEVELOPMENT, 1),
                        PlotOrderAssignment(FileKey.of("Scene/Memo.md"), PlotStage.SETUP, 1),
                    ),
                )
            }

            assertEquals(setOf(first.name, second.name), scene.listFiles().orEmpty().map { it.name }.toSet())
            assertContentEquals(firstBytes, first.readBytes())
            assertContentEquals(secondBytes, second.readBytes())
            assertNull(NoteFile.parse(second.readText()).id)
            assertContentEquals(configBytes, File(project, ".machum.json").readBytes())
            assertEquals(bookmarks, fileManager.bookmarks.value)
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

    @Test
    fun internalUntitledFoldersFillSuffixGapsAndPreserveAllExistingItems() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
        manager.writeConfig(ProjectConfig())
        File(project, "무제").mkdirs()
        File(project, "무제/keep.md").writeText("keep")
        File(project, "무제_2").writeText("different type")
        val general = FolderConfig(type = FolderType.GENERAL, plotEnabled = false, autoTags = emptyList())

        val first = assertNotNull(manager.createProjectFolder("무제", general))
        val next = assertNotNull(manager.createProjectFolder("무제", general))

        assertEquals("무제_1", first.key.relativePath)
        assertEquals("무제_3", next.key.relativePath)
        assertEquals(general, manager.projectConfig.value?.folders?.get("무제_1"))
        assertEquals("keep", File(project, "무제/keep.md").readText())
        assertEquals("different type", File(project, "무제_2").readText())
        assertTrue(first.platformFile.file.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun generalWorkspaceUntitledFolderDoesNotWriteManagementConfig() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project), workspaceKind = WorkspaceKind.GENERAL))
        manager.writeConfig(ProjectConfig())
        val folder = assertNotNull(manager.createProjectFolder("무제", FolderConfig(type = FolderType.GENERAL)))
        assertEquals("무제", folder.key.relativePath)
        assertFalse(File(project, ".machum.json").exists())
        assertTrue(folder.platformFile.file.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun generalWorkspaceFolderCreationDoesNotReadPreservedManagementPath() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project), workspaceKind = WorkspaceKind.GENERAL))
        manager.writeConfig(ProjectConfig())
        val preserved = File(project, ".machum.json").apply { mkdirs() }
        File(preserved, "keep.txt").writeText("preserved")
        val folder = assertNotNull(manager.createProjectFolder("무제", FolderConfig(type = FolderType.GENERAL)))
        assertEquals("무제", folder.key.relativePath)
        assertTrue(folder.platformFile.file.isDirectory)
        assertTrue(preserved.isDirectory)
        assertEquals("preserved", File(preserved, "keep.txt").readText())
    }

    @Test
    fun untitledFileCreationPreservesCaseInsensitiveAndDirectoryCollisions() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project), workspaceKind = WorkspaceKind.GENERAL))
        File(project, "무제.MD").writeText("keep")
        File(project, "무제_2.md").mkdirs()
        File(project, "무제_2.md/keep.txt").writeText("folder keep")
        val folder = ProjectFolder(FolderKey.Base, PlatformFile(project))
        val first = assertNotNull(manager.createProjectFile(folder, "무제"))
        val next = assertNotNull(manager.createProjectFile(folder, "무제"))
        assertEquals("무제_1.md", first.key.fileName)
        assertEquals("무제_3.md", next.key.fileName)
        assertEquals("keep", File(project, "무제.MD").readText())
        assertEquals("folder keep", File(project, "무제_2.md/keep.txt").readText())
        assertEquals("", first.platformFile.file.readText())
        assertFalse(File(project, ".machum.json").exists())
    }

    @Test
    fun fileCreationWritesPreparedPlotTagsBodyAndProjectIdentityTogether() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
        val folder = ProjectFolder(FolderKey.Base, PlatformFile(project))
        val prepared = NoteFile.parse("body").withPlotStage(PlotStage.CLIMAX).withTags(listOf("auto", "manual"))
        val created = assertNotNull(manager.createProjectFile(folder, "4-1. 무제", prepared))
        val note = NoteFile.parse(created.platformFile.file.readText())
        assertEquals("4-1. 무제.md", created.key.fileName)
        assertNotNull(note.id)
        assertEquals(listOf("Project", "auto", "manual"), note.tags)
        assertEquals(PlotStage.CLIMAX, note.plotStage)
        assertTrue(created.platformFile.file.readText().endsWith("body"))
    }

    @Test
    fun fileCreationRejectsStaleFolderOutsideCurrentWorkspace() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
        val outside = File(project.parentFile, "Outside").apply { mkdirs() }
        assertNull(manager.createProjectFile(ProjectFolder(FolderKey.Base, PlatformFile(outside)), "무제"))
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun incompleteFileRetryCompletesSameFileWithoutAllocatingSuffix() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
        val failure = assertFailsWith<FileCreationIncompleteException> {
            manager.createFileWithContentWriter(PlatformFile(project), "무제", "complete") { output, _ ->
                output.write("partial".toByteArray())
                throw java.io.IOException("storage write failed after partial bytes")
            }
        }
        val file = File(project, "무제.md")
        assertEquals(file.absolutePath, failure.file.file.absolutePath)
        assertEquals("partial", file.readText())
        assertEquals("partial", failure.observedContent)
        assertEquals("complete", failure.expectedContent)
        val result = manager.retryProjectFileCreation(ProjectFolder(FolderKey.Base, PlatformFile(project)), failure)
        assertEquals("무제.md", result.key.fileName)
        assertEquals("complete", file.readText())
        assertFalse(File(project, "무제_1.md").exists())
    }

    @Test
    fun folderConfigPartialWriteFailureRestoresOriginalAndRemovesOnlyNewEmptyFolder() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
        val originalConfig = assertNotNull(manager.writeConfig(ProjectConfig()))
        val configFile = File(project, ".machum.json")
        val originalBytes = configFile.readBytes()
        File(project, "무제").mkdirs()
        File(project, "무제/keep.md").writeText("existing")
        assertFailsWith<IllegalStateException> {
            manager.createProjectFolderWithConfigWriter("무제", FolderConfig(type = FolderType.GENERAL)) { _, _ ->
                assertTrue(File(project, "무제_1").isDirectory, "failure must happen after physical creation")
                configFile.writeText("{partial")
                throw java.io.IOException("config write failed")
            }
        }
        assertContentEquals(originalBytes, configFile.readBytes())
        assertEquals(originalConfig, manager.projectConfig.value)
        assertFalse(File(project, "무제_1").exists())
        assertEquals("existing", File(project, "무제/keep.md").readText())
    }

    @Test
    fun incompleteFileRetryRefusesExternalChangesAndUnknownSnapshots() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
        val file = File(project, "무제.md").apply { writeText("external edit") }
        val folder = ProjectFolder(FolderKey.Base, PlatformFile(project))
        val failure = FileCreationIncompleteException(PlatformFile(file), "complete", "partial", IllegalStateException("write failure"))
        assertFailsWith<IllegalStateException> { manager.retryProjectFileCreation(folder, failure) }
        val unknown = FileCreationIncompleteException(PlatformFile(file), "complete", null, IllegalStateException("unreadable"))
        assertFailsWith<IllegalStateException> { manager.retryProjectFileCreation(folder, unknown) }
        assertEquals("external edit", file.readText())
        assertFalse(File(project, "무제_1.md").exists())
    }

    @Test
    fun simultaneousInternalFolderRequestsProduceDistinctFolders() = withFileManager { manager, project ->
        manager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
        manager.writeConfig(ProjectConfig())
        kotlinx.coroutines.coroutineScope {
            val first = async { manager.createProjectFolder("무제", FolderConfig(type = FolderType.GENERAL)) }
            val second = async { manager.createProjectFolder("무제", FolderConfig(type = FolderType.GENERAL)) }
            assertEquals(setOf("무제", "무제_1"), setOf(assertNotNull(first.await()).key.relativePath, assertNotNull(second.await()).key.relativePath))
        }
        assertTrue(File(project, "무제").isDirectory)
        assertTrue(File(project, "무제_1").isDirectory)
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

    private fun withFolderRenameFixture(
        selectedInsideFolder: Boolean = true,
        workspaceKind: WorkspaceKind = WorkspaceKind.PROJECT,
        block: suspend CoroutineScope.(FolderRenameFixture) -> Unit,
    ) = runBlocking {
        val testRoot = Files.createTempDirectory("machum-folder-rename-transaction").toFile()
        val project = File(testRoot, "Project").apply { mkdirs() }
        val originalDirectory = File(project, "Character").apply { mkdirs() }
        val hero = File(originalDirectory, "Hero.md").apply {
            writeText("\uFEFF---\r\nid: hero-id\r\ntags: [manual, Project]\r\n---\r\nhero")
        }
        val scene = File(project, "Scene").apply { mkdirs() }
        val other = File(scene, "Other.md").apply { writeText("other") }
        val dataStore = InterruptiblePreferencesDataStore()

        try {
            val fileManager = FileManager(dataStore)
            val selectedFile = if (selectedInsideFolder) hero else other
            val selectedPath = if (selectedInsideFolder) "Character/Hero.md" else "Scene/Other.md"
            fileManager.setPreferences(
                Bookmarks(
                    projectData = PlatformFile(project),
                    fileData = PlatformFile(selectedFile),
                    fileRelativePath = selectedPath,
                    workspaceKind = workspaceKind,
                ),
            )
            val folderConfig = FolderConfig(
                type = FolderType.GENERAL,
                autoTags = listOf("character", "manual"),
            )
            assertNotNull(
                fileManager.writeConfig(
                    ProjectConfig(
                        folders = mapOf(
                            "Character" to folderConfig,
                            "Scene" to FolderConfig(type = FolderType.GENERAL),
                        ),
                        fileIds = mapOf(
                            "hero-id" to "Character/Hero.md",
                            "other-id" to "Scene/Other.md",
                        ),
                    ),
                ),
            )
            val configFile = File(project, ".machum.json")
            if (workspaceKind == WorkspaceKind.PROJECT) {
                val customConfigRaw = configFile.readText()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replace("\n", "\r\n") + "\r\n"
                configFile.writeText(customConfigRaw)
            }

            block(
                FolderRenameFixture(
                    fileManager = fileManager,
                    dataStore = dataStore,
                    project = project,
                    originalDirectory = originalDirectory,
                    renamedDirectory = File(project, "Renamed Character"),
                    hero = hero,
                    renamedHero = File(project, "Renamed Character/Hero.md"),
                    configFile = configFile,
                    folderConfig = folderConfig,
                    originalConfig = assertNotNull(fileManager.projectConfig.value),
                    originalBookmarks = fileManager.bookmarks.value,
                    heroBytes = hero.readBytes(),
                    heroLastModified = hero.lastModified(),
                    configBytes = configFile.takeIf(File::exists)?.readBytes(),
                ),
            )
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun fileManagerConfig(fixture: FolderRenameFixture): ProjectConfig =
        assertNotNull(fixture.fileManager.projectConfig.value)
}

private data class FolderRenameFixture(
    val fileManager: FileManager,
    val dataStore: InterruptiblePreferencesDataStore,
    val project: File,
    val originalDirectory: File,
    val renamedDirectory: File,
    val hero: File,
    val renamedHero: File,
    val configFile: File,
    val folderConfig: FolderConfig,
    val originalConfig: ProjectConfig,
    val originalBookmarks: Bookmarks,
    val heroBytes: ByteArray,
    val heroLastModified: Long,
    val configBytes: ByteArray?,
) {
    suspend fun renameFolder(): FolderRenameUpdate? = fileManager.renameProjectFolder(
        folder = ProjectFolder(FolderKey.of("Character"), PlatformFile(originalDirectory)),
        newName = "Renamed Character",
        folderConfig = folderConfig,
    )

    suspend fun assertFullyRolledBack() {
        assertTrue(originalDirectory.isDirectory)
        assertFalse(renamedDirectory.exists())
        assertTrue(hero.isFile)
        assertContentEquals(heroBytes, hero.readBytes())
        assertEquals(heroLastModified, hero.lastModified())
        if (configBytes == null) assertFalse(configFile.exists())
        else assertContentEquals(configBytes, configFile.readBytes())
        assertEquals(originalConfig, fileManager.projectConfig.value)
        assertEquals(originalBookmarks, fileManager.bookmarks.value)
        val persistedBookmarks = fileManager.getPreferences()
        assertEquals(originalBookmarks.fileRelativePath, persistedBookmarks.fileRelativePath)
        assertEquals(hero.absolutePath, persistedBookmarks.fileData?.file?.absolutePath)
    }
}

private enum class InterruptBehavior {
    AWAIT_CANCELLATION,
    THROW_CANCELLATION,
    THROW_FAILURE,
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
            InterruptBehavior.THROW_FAILURE -> {
                nextSignal.getAndSet(null)?.complete(Unit)
                throw IllegalStateException("injected preference failure")
            }
            null -> Unit
        }
        return updated
    }
}
