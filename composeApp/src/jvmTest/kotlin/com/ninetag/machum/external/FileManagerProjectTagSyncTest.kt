package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileManagerProjectTagSyncTest {
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

            indexer.prepare(project)
            val secondResult = indexer.index(project)

            assertEquals(0, secondResult.updated)
            assertEquals(2, secondResult.unchanged)
            assertIs<ProjectIndexState.Ready>(indexer.state.value)
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
}
