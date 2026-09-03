package com.ninetag.machum.commit

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.NoteFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectCommitServiceTest {
    @Test
    fun commitsProjectSnapshotsAndWritesOnlyNewContentBlobs() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val service = ProjectCommitService(fileManager)

            val initialPreview = service.preview(PlatformFile(project))
            assertEquals(1, initialPreview.changes.size)
            assertTrue(initialPreview.changes.all { it.kind == CommitChangeKind.ADDED })

            val first = service.commit(PlatformFile(project), "initial")
            assertEquals(null, first.commit.parentId)
            assertEquals(1, blobFiles(project).size)
            assertTrue(service.preview(PlatformFile(project)).changes.isEmpty())

            File(project, "Draft.md").writeText(note("draft-id", "changed"))
            val secondPreview = service.preview(PlatformFile(project))
            assertEquals(CommitChangeKind.MODIFIED, secondPreview.changes.single().kind)

            val second = service.commit(PlatformFile(project), "change draft")
            assertEquals(first.commit.id, second.commit.parentId)
            assertEquals(2, blobFiles(project).size)
            val contentDiff = service.diff(PlatformFile(project), second.commit.id, "draft-id")
            assertTrue(contentDiff.lines.any { it.kind == LineDiffKind.ADDED && it.text == "changed" })
            assertTrue(contentDiff.lines.any { it.kind == LineDiffKind.DELETED && it.text == "first" })

            File(project, "Draft.md").renameTo(File(project, "Renamed.md"))
            val renamePreview = service.preview(PlatformFile(project))
            assertEquals(CommitChangeKind.RENAMED, renamePreview.changes.single().kind)
            val third = service.commit(PlatformFile(project), "rename draft")
            assertEquals(2, blobFiles(project).size)

            val history = service.history(PlatformFile(project))
            assertEquals(listOf("rename draft", "change draft", "initial"), history.map { it.commit.message })
            assertEquals(third.commit.id, history.first().commit.id)
            assertEquals(CommitChangeKind.RENAMED, history.first().changes.single().kind)
        }
    }

    @Test
    fun projectConfigChangesAreNotTracked() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val service = ProjectCommitService(fileManager)
            service.commit(PlatformFile(project), "initial")

            File(project, ".machum.json").writeText(
                """{"folders":{"":{"type":"general","plotEnabled":false,"autoTags":[]}},"fileIds":{}}""",
            )

            assertTrue(service.preview(PlatformFile(project)).changes.isEmpty())
            assertFailsWith<IllegalStateException> {
                service.commit(PlatformFile(project), "config only")
            }
        }
    }

    @Test
    fun legacyProjectConfigEntryIsIgnoredByPreviewAndHistory() = runBlocking {
        withProject { project, fileManager ->
            val draftContent = note("draft-id", "first")
            val configContent = File(project, ".machum.json").readText()
            val draftFile = File(project, "Draft.md").apply { writeText(draftContent) }
            val canonicalDraftContent = fileManager.readMarkdown(PlatformFile(draftFile)).inject()

            val store = FileCommitStore(fileManager, PlatformFile(project))
            val draftHash = sha256Utf8(canonicalDraftContent)
            val configHash = sha256Utf8(configContent)
            store.writeBlob(draftHash, canonicalDraftContent)
            store.writeBlob(configHash, configContent)
            val treeHash = store.writeTree(
                CommitTree(
                    entries = listOf(
                        CommitTreeEntry("draft-id", "Draft.md", draftHash),
                        CommitTreeEntry("machum:project-config", ".machum.json", configHash),
                    ),
                ),
            )
            val legacyCommit = ProjectCommit(
                id = "legacy-config-commit",
                treeHash = treeHash,
                createdAtEpochMillis = 1L,
                message = "legacy",
            )
            store.writeCommit(legacyCommit)
            store.updateHead(legacyCommit.id)

            val service = ProjectCommitService(fileManager)
            val preview = service.preview(PlatformFile(project))
            assertTrue(preview.changes.isEmpty(), preview.changes.toString())
            assertEquals(
                listOf("Draft.md"),
                service.history(PlatformFile(project)).single().changes.map(CommitChange::displayPath),
            )
        }
    }

    @Test
    fun deletionIsTrackedWithoutWritingAnotherBlob() = runBlocking {
        withProject { project, fileManager ->
            val note = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val service = ProjectCommitService(fileManager)
            service.commit(PlatformFile(project), "initial")
            val initialBlobCount = blobFiles(project).size

            assertTrue(note.delete())
            val preview = service.preview(PlatformFile(project))
            assertEquals(CommitChangeKind.DELETED, preview.changes.single().kind)
            service.commit(PlatformFile(project), "delete draft")

            assertEquals(initialBlobCount, blobFiles(project).size)
        }
    }

    @Test
    fun restoresAnOlderSnapshotWithoutMovingHead_andBlocksDirtyRestore() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val service = ProjectCommitService(fileManager)
            val first = service.commit(PlatformFile(project), "initial")

            draft.writeText(note("draft-id", "second"))
            File(project, "Added.md").writeText(note("added-id", "added"))
            val second = service.commit(PlatformFile(project), "second")
            assertTrue(service.preview(PlatformFile(project)).changes.isEmpty())

            val restored = service.restore(PlatformFile(project), first.commit.id)

            assertEquals(first.commit.id, restored.targetCommit.id)
            assertEquals("first", NoteFile.parse(draft.readText()).body)
            assertTrue(!File(project, "Added.md").exists())
            assertEquals(second.commit.id, service.history(PlatformFile(project)).first().commit.id)
            assertEquals(
                setOf(CommitChangeKind.MODIFIED, CommitChangeKind.DELETED),
                service.preview(PlatformFile(project)).changes.mapTo(mutableSetOf()) { it.kind },
            )
            assertFailsWith<UncommittedChangesException> {
                service.restore(PlatformFile(project), first.commit.id)
            }
        }
    }

    @Test
    fun restoreRecreatesHistoricalFolderPathWithoutChangingProjectConfig() = runBlocking {
        withProject { project, fileManager ->
            val characterFolder = File(project, "Character").apply { mkdirs() }
            val originalConfig =
                """{"folders":{"":{"type":"default","plotEnabled":false,"autoTags":[]},"Character":{"type":"general","plotEnabled":false,"autoTags":[]}},"fileIds":{}}"""
            File(project, ".machum.json").writeText(originalConfig)
            File(characterFolder, "Hero.md").writeText(note("hero-id", "hero"))
            val service = ProjectCommitService(fileManager)
            val first = service.commit(PlatformFile(project), "character in folder")

            assertTrue(File(characterFolder, "Hero.md").renameTo(File(project, "Hero.md")))
            val currentConfig =
                """{"folders":{"":{"type":"default","plotEnabled":false,"autoTags":[]}},"fileIds":{}}"""
            File(project, ".machum.json").writeText(currentConfig)
            fileManager.reloadCurrentProjectConfig()
            service.commit(PlatformFile(project), "move character")

            service.restore(PlatformFile(project), first.commit.id)

            assertTrue(File(characterFolder, "Hero.md").isFile)
            assertTrue(!File(project, "Hero.md").exists())
            assertEquals("hero", NoteFile.parse(File(characterFolder, "Hero.md").readText()).body)
            assertEquals(currentConfig, File(project, ".machum.json").readText())
            assertTrue(fileManager.projectConfig.value?.folders?.containsKey("Character") == false)
        }
    }

    private suspend fun withProject(block: suspend (File, FileManager) -> Unit) {
        val root = withContext(Dispatchers.IO) {
            Files.createTempDirectory("machum-commit")
        }.toFile()
        val project = File(root, "Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(root, "preferences.preferences_pb").absolutePath.toPath()
        }
        try {
            val fileManager = FileManager(dataStore)
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))
            File(project, ".machum.json").writeText(
                """{"folders":{"":{"type":"default","plotEnabled":false,"autoTags":[]}},"fileIds":{}}""",
            )
            block(project, fileManager)
        } finally {
            dataStoreScope.cancel()
            root.deleteRecursively()
        }
    }

    private fun blobFiles(project: File): List<File> =
        File(project, ".machum/blobs").listFiles()?.toList().orEmpty()

    private fun note(id: String, body: String): String = """
        ---
        id: $id
        ---

        $body
    """.trimIndent()
}
