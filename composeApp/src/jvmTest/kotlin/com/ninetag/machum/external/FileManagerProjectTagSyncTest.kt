package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.entity.PlotStage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileManagerProjectTagSyncTest {
    @Test
    fun readMarkdownDoesNotChangeFilesInEitherWorkspaceModeOrAfterSelectionChanges() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-pure-markdown-read").toFile()
        val projectDirectory = File(testRoot, "Current Project").apply { mkdirs() }
        val externalDirectory = File(testRoot, "General Notes").apply { mkdirs() }
        val nextProject = File(testRoot, "Next Project").apply { mkdirs() }
        val note = File(externalDirectory, "Idea.md").apply {
            writeText("\uFEFF---\r\ntags:\r\n  - manual\r\ncustom: keep\r\nplot: 0) 프롤로그\r\n---\r\n\r\nraw idea")
            check(setLastModified(1_700_000_000_000))
        }
        val originalBytes = note.readBytes()
        val originalModified = note.lastModified()
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            val selections = listOf(
                Bookmarks(projectData = PlatformFile(projectDirectory)),
                Bookmarks(projectData = PlatformFile(externalDirectory), workspaceKind = WorkspaceKind.GENERAL),
                Bookmarks(projectData = PlatformFile(nextProject)),
            )
            selections.forEach { selection ->
                fileManager.setPreferences(selection)

                val loaded = fileManager.readMarkdown(PlatformFile(note))

                assertNull(loaded.id)
                assertEquals(listOf("manual"), loaded.tags)
                assertEquals("0) 프롤로그", loaded.plot)
                assertEquals("raw idea", loaded.body)
                assertContentEquals(originalBytes, note.readBytes())
                assertEquals(originalModified, note.lastModified())
            }
        } finally {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun explicitProjectMetadataUsesTheCapturedTagAndPreservesExistingMetadataOnRepeat() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-explicit-project-metadata").toFile()
        val originalProject = File(testRoot, "Captured Project").apply { mkdirs() }
        val nextProject = File(testRoot, "Current Project").apply { mkdirs() }
        val note = File(originalProject, "Idea.md").apply {
            writeText("\uFEFF---\r\nid: stable-id\r\ntags:\r\n  - manual\r\ncustom: keep\r\nplot: 0) 프롤로그\r\n---\r\n\r\nraw idea")
        }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(nextProject)))

            val update = fileManager.ensureProjectMetadata(PlatformFile(note), "Captured_Project")

            assertTrue(update.changed)
            assertEquals("stable-id", update.noteFile.id)
            assertEquals(listOf("Captured_Project", "manual"), update.noteFile.tags)
            assertEquals("0) 프롤로그", update.noteFile.plot)
            assertEquals("raw idea", update.noteFile.body)
            val persisted = note.readText()
            assertTrue(persisted.startsWith("\uFEFF---\r\n"))
            assertTrue(persisted.contains("custom: keep\r\n"))
            assertFalse(persisted.contains("Current_Project"))
            assertEquals(update.noteFile.inject(), persisted)
            assertTrue(note.setLastModified(1_700_000_000_000))
            val firstBytes = note.readBytes()
            val firstModified = note.lastModified()

            val repeated = fileManager.ensureProjectMetadata(PlatformFile(note), "Captured_Project")

            assertFalse(repeated.changed)
            assertEquals("stable-id", repeated.noteFile.id)
            assertContentEquals(firstBytes, note.readBytes())
            assertEquals(firstModified, note.lastModified())
        } finally {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun projectNameIsAddedToRootAndDirectFolderFilesAsNormalizedTag() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-project-tag").toFile()
        val projectDirectory = File(testRoot, "폴더 1").apply { mkdirs() }
        val characterDirectory = File(projectDirectory, "Character").apply { mkdirs() }
        val rootFile = File(projectDirectory, "Root.md").apply {
            writeText("---\ntags:\n  - 캐릭터\ncustom: 유지\n---\n\n루트 본문")
        }
        val characterFile = File(characterDirectory, "Hero.md").apply { writeText("인물 본문") }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            val project = PlatformFile(projectDirectory)
            fileManager.setPreferences(Bookmarks(projectData = project))
            val indexer = ProjectIndexer(fileManager)
            indexer.prepare(project)

            val result = indexer.index(project)

            assertEquals(2, result.total)
            assertEquals(2, result.updated)
            assertEquals(0, result.failed)
            assertEquals(
                listOf("폴더_1", "캐릭터"),
                fileManager.readMarkdown(PlatformFile(rootFile)).tags,
            )
            assertEquals(
                listOf("폴더_1"),
                fileManager.readMarkdown(PlatformFile(characterFile)).tags,
            )
            assertTrue(rootFile.readText().contains("custom: 유지"))
            assertTrue(rootFile.readText().endsWith("루트 본문"))
            assertTrue(characterFile.readText().endsWith("인물 본문"))
            assertIs<ProjectIndexState.Ready>(indexer.state.value)
            val hierarchySnapshot = assertNotNull(result.hierarchySnapshot)
            assertEquals(project.toString(), hierarchySnapshot.projectLocation)
            assertEquals(
                listOf(FolderKey.Base, FolderKey.of("Character")),
                hierarchySnapshot.folders.map(ProjectFolder::key),
            )
            assertEquals(
                setOf(FileKey.of("Root.md"), FileKey.of("Character/Hero.md")),
                hierarchySnapshot.filesByFolder.values.flatten().map(ProjectFile::key).toSet(),
            )

            indexer.prepare(project)
            val secondResult = indexer.index(project)

            assertEquals(0, secondResult.updated)
            assertEquals(2, secondResult.unchanged)
            assertTrue(
                assertNotNull(secondResult.hierarchySnapshot).activationGeneration >
                    hierarchySnapshot.activationGeneration,
            )
            assertIs<ProjectIndexState.Ready>(indexer.state.value)
            assertNull(
                indexer.takeHierarchySnapshot(
                    project.toString(),
                    hierarchySnapshot.activationGeneration,
                ),
                "a previous activation must not consume the current hierarchy snapshot",
            )
            assertNotNull(
                indexer.takeHierarchySnapshot(
                    project.toString(),
                    secondResult.activationGeneration,
                ),
            )
            assertNull(
                indexer.takeHierarchySnapshot(
                    project.toString(),
                    secondResult.activationGeneration,
                ),
                "the same activation snapshot must be consumed only once",
            )
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
        Unit
    }

    @Test
    fun indexingBomCrLfFileWithoutFrontMatterKeepsBomOnlyAtDocumentStart() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-project-bom").toFile()
        val projectDirectory = File(testRoot, "프로젝트").apply { mkdirs() }
        val note = File(projectDirectory, "본문.md").apply {
            writeText("\uFEFF첫 줄\r\n둘째 줄")
        }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            val project = PlatformFile(projectDirectory)
            fileManager.setPreferences(Bookmarks(projectData = project))
            val indexer = ProjectIndexer(fileManager)
            indexer.prepare(project)

            val result = indexer.index(project)
            val indexed = note.readText()

            assertEquals(1, result.updated)
            assertTrue(indexed.startsWith("\uFEFF---\r\n"))
            assertEquals(1, indexed.count { it == '\uFEFF' })
            assertTrue(indexed.endsWith("---\r\n\r\n첫 줄\r\n둘째 줄"))
            assertEquals(listOf("프로젝트"), NoteFile.parse(indexed).tags)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun projectIndexRetriesWhenTheFileChangesBetweenItsBodyAndModificationTimeReads() = runBlocking {
        val testRoot = Files.createTempDirectory("machum-project-index-stable-mtime").toFile()
        val projectDirectory = File(testRoot, "Project").apply { mkdirs() }
        val note = File(projectDirectory, "Scene.md").apply {
            writeText(
                """---
id: stable-id
tags:
  - Project
plot: 1) 발단
---

scene""",
            )
        }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            val fileManager = FileManager(dataStore)
            val project = PlatformFile(projectDirectory)
            fileManager.setPreferences(Bookmarks(projectData = project))
            val modifiedReads = AtomicInteger()
            fileManager.lastModifiedReader = {
                when (modifiedReads.incrementAndGet()) {
                    1 -> 100L
                    2 -> {
                        note.writeText(
                            NoteFile.parse(note.readText())
                                .withPlotStage(PlotStage.DEVELOPMENT)
                                .inject(),
                        )
                        200L
                    }
                    else -> 200L
                }
            }
            val indexer = ProjectIndexer(fileManager)
            indexer.prepare(project)

            val result = indexer.index(project)

            assertEquals(0, result.failed)
            assertEquals(4, modifiedReads.get())
            val indexed = checkNotNull(
                fileManager.workspaceMetadataIndex
                    .snapshot(project, WorkspaceKind.PROJECT)
                    ?.entries
                    ?.get(FileKey.of(note.name)),
            )
            assertEquals(200L, indexed.modifiedAt)
            assertEquals(PlotStage.DEVELOPMENT, indexed.plot?.stage)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }
}
