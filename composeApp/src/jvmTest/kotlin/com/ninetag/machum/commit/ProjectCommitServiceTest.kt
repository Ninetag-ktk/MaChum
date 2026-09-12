package com.ninetag.machum.commit

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.createFolder
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.ProjectConfig
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectCommitServiceTest {
    @Test
    fun previewAndHistoryReadLegacyTxtSuffixedBlobFiles() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val platformProject = PlatformFile(project)
            val service = ProjectCommitService(fileManager)
            val commit = service.commit(platformProject, "initial")
            val blobs = blobFiles(project)
            assertTrue(blobs.isNotEmpty())
            blobs.forEach { blob ->
                assertTrue(blob.renameTo(File(blob.parentFile, "${blob.name}.txt")))
            }

            draft.writeText(note("draft-id", "changed"))

            val preview = service.preview(platformProject)
            assertEquals(CommitChangeKind.MODIFIED, preview.changes.single().kind)
            assertEquals(commit.commit.id, service.history(platformProject).single().commit.id)
        }
    }

    @Test
    fun commitsProjectSnapshotsAndWritesOnlyNewContentBlobs() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val service = ProjectCommitService(fileManager)

            val initialPreview = service.preview(PlatformFile(project))
            assertEquals(1, initialPreview.changes.size)
            assertTrue(initialPreview.changes.all { it.kind == CommitChangeKind.ADDED })
            assertEquals(setOf("draft-id"), initialPreview.currentFileIds)

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
    fun previewUsesTheExplicitProjectWithoutMutatingItsMarkdown() = runBlocking {
        withProject { project, fileManager ->
            val original = "---\r\nid: 'draft-id'\r\ncustom:  keep # exact\r\n---\r\n\r\ndraft"
            val draft = File(project, "Draft.md").apply {
                writeText(original)
                assertTrue(setLastModified(1_234_000L))
            }
            val otherProject = File(project.parentFile, "Other Project").apply { mkdirs() }
            File(otherProject, "Other.md").writeText(note("other-id", "other"))
            fileManager.setPreferences(Bookmarks(projectData = PlatformFile(otherProject)))
            val bytesBefore = draft.readBytes()
            val modifiedBefore = draft.lastModified()

            val preview = ProjectCommitService(fileManager).preview(PlatformFile(project))

            assertEquals(1, preview.changes.size)
            assertEquals(setOf("draft-id"), preview.currentFileIds)
            assertContentEquals(bytesBefore, draft.readBytes())
            assertEquals(modifiedBefore, draft.lastModified())
            assertEquals(original, draft.readText())
        }
    }

    @Test
    fun previewAndWorkingDiffPreserveRawFormattingBytesMtimeAndHash() = runBlocking {
        withProject { project, fileManager ->
            val platformProject = PlatformFile(project)
            val draft = File(project, "Draft.md")
            val initial = rawFormattedNote("format-id", tagsAsFlow = true, body = "same body")
            draft.writeText(initial)
            val service = ProjectCommitService(fileManager)
            service.commit(platformProject, "raw baseline")
            assertContentEquals(initial.toByteArray(), draft.readBytes())

            val formatOnlyEdit = rawFormattedNote(
                id = "format-id",
                tagsAsFlow = false,
                body = "same body",
            )
            draft.writeText(formatOnlyEdit)
            assertTrue(draft.setLastModified(2_345_000L))
            val bytesBefore = draft.readBytes()
            val modifiedBefore = draft.lastModified()

            val firstPreview = service.preview(platformProject)
            val workingDiff = service.diff(platformProject, commitId = null, fileId = "format-id")
            val secondPreview = service.preview(platformProject)

            assertEquals(CommitChangeKind.MODIFIED, firstPreview.changes.single().kind)
            assertEquals(firstPreview.workingTreeHash, secondPreview.workingTreeHash)
            assertEquals(firstPreview.changes, secondPreview.changes)
            assertTrue(workingDiff.lines.any { it.kind == LineDiffKind.ADDED })
            assertTrue(workingDiff.lines.any { it.kind == LineDiffKind.DELETED })
            assertContentEquals(bytesBefore, draft.readBytes())
            assertEquals(modifiedBefore, draft.lastModified())
        }
    }

    @Test
    fun missingIdFailsWithoutMutationAndExplicitRepairProducesStableAddedPreview() = runBlocking {
        withProject { project, fileManager ->
            val platformProject = PlatformFile(project)
            val original = "\uFEFF---\r\ntags: [manual]\r\ncustom: keep\r\n---\r\n\r\nbody"
            val draft = File(project, "Draft.md").apply {
                writeText(original)
                assertTrue(setLastModified(3_456_000L))
            }
            val service = ProjectCommitService(fileManager)
            val bytesBefore = draft.readBytes()
            val modifiedBefore = draft.lastModified()

            val error = assertFailsWith<CommitConflictException> {
                service.preview(platformProject)
            }

            assertTrue(error.message.orEmpty().contains("파일 ID가 없습니다"))
            assertContentEquals(bytesBefore, draft.readBytes())
            assertEquals(modifiedBefore, draft.lastModified())

            val update = fileManager.ensureProjectMetadata(PlatformFile(draft), "Project")
            assertTrue(update.changed)
            assertTrue(update.noteFile.id != null)
            assertTrue("Project" in update.noteFile.tags)
            val repairedBytes = draft.readBytes()
            val repairedModified = draft.lastModified()

            val firstPreview = service.preview(platformProject)
            val secondPreview = service.preview(platformProject)

            assertEquals(CommitChangeKind.ADDED, firstPreview.changes.single().kind)
            assertEquals(update.noteFile.id, firstPreview.changes.single().fileId)
            assertEquals(firstPreview.workingTreeHash, secondPreview.workingTreeHash)
            assertContentEquals(repairedBytes, draft.readBytes())
            assertEquals(repairedModified, draft.lastModified())
        }
    }

    @Test
    fun legacyProjectConfigEntryIsIgnoredByPreviewAndHistory() = runBlocking {
        withProject { project, fileManager ->
            val draftContent = note("draft-id", "first")
            val configContent = File(project, ".machum.json").readText()
            val draftFile = File(project, "Draft.md").apply { writeText(draftContent) }
            val canonicalDraftContent = draftFile.readText()

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
                id = CommitObjectCodec.calculateCommitId(
                    parentId = null,
                    treeHash = treeHash,
                    createdAtEpochMillis = 1L,
                    message = "legacy",
                ),
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

    @Test
    fun restoresHistoricalFileContentWhileKeepingCurrentIdentityPlotAndPath() = runBlocking {
        withProject { project, fileManager ->
            val historicalFolder = File(project, "1. Concept").apply { mkdirs() }
            val currentFolder = File(project, "4. Scene").apply { mkdirs() }
            val historical = File(historicalFolder, "1. Original.md").apply {
                writeText(
                    richNote(
                        id = "draft-id",
                        tags = listOf("historical_tag"),
                        aliases = listOf("historical alias"),
                        plot = "1) 발단",
                        custom = "historical",
                        body = "historical body",
                    ),
                )
            }
            val service = ProjectCommitService(fileManager)
            val first = service.commit(PlatformFile(project), "initial")

            val current = File(currentFolder, "9-9. Current.md")
            assertTrue(historical.renameTo(current))
            current.writeText(
                richNote(
                    id = "draft-id",
                    tags = listOf("current_tag"),
                    aliases = listOf("current alias"),
                    plot = "4) 절정",
                    custom = "current",
                    body = "current body",
                ),
            )
            val second = service.commit(PlatformFile(project), "move and edit")

            val result = service.restoreFileContent(
                project = PlatformFile(project),
                commitId = first.commit.id,
                fileId = "draft-id",
                side = CommitFileSide.AFTER,
            )

            assertTrue(result.changed)
            assertEquals("4. Scene/9-9. Current.md", result.previousPath)
            assertEquals(result.previousPath, result.restoredPath)
            assertTrue(current.isFile)
            assertFalse(File(historicalFolder, "1. Original.md").exists())
            val restored = NoteFile.parse(current.readText())
            assertEquals("draft-id", restored.id)
            assertEquals("4) 절정", restored.plot)
            assertEquals("historical body", restored.body)
            assertTrue("historical_tag" in restored.tags)
            assertTrue("current_tag" in restored.tags)
            assertTrue("Project" in restored.tags)
            assertEquals(listOf("historical alias"), restored.aliases)
            assertTrue("custom: historical" in current.readText())
            assertFalse("custom: current" in current.readText())
            assertEquals(second.commit.id, service.history(PlatformFile(project)).first().commit.id)
        }
    }

    @Test
    fun fileContentRestoreRejectsMissingSideAndDirtyTarget() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val service = ProjectCommitService(fileManager)
            val first = service.commit(PlatformFile(project), "initial")

            assertFailsWith<IllegalArgumentException> {
                service.restoreFileContent(
                    PlatformFile(project),
                    first.commit.id,
                    "draft-id",
                    CommitFileSide.BEFORE,
                )
            }

            draft.writeText(note("draft-id", "dirty"))
            assertFailsWith<UncommittedChangesException> {
                service.restoreFileContent(
                    PlatformFile(project),
                    first.commit.id,
                    "draft-id",
                    CommitFileSide.AFTER,
                )
            }
            assertEquals("dirty", NoteFile.parse(draft.readText()).body)
        }
    }

    @Test
    fun fileContentRestoreRemovesHistoricalPlotWhenCurrentPlotIsNull() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply {
                writeText(
                    richNote(
                        id = "draft-id",
                        tags = listOf("historical"),
                        aliases = listOf("old alias"),
                        plot = "1) 발단",
                        custom = "old",
                        body = "old body",
                    ),
                )
            }
            val service = ProjectCommitService(fileManager)
            val first = service.commit(PlatformFile(project), "initial")
            draft.writeText(
                richNote(
                    id = "draft-id",
                    tags = listOf("current"),
                    aliases = listOf("current alias"),
                    plot = null,
                    custom = "current",
                    body = "current body",
                ),
            )
            service.commit(PlatformFile(project), "remove plot")

            service.restoreFileContent(
                PlatformFile(project),
                first.commit.id,
                "draft-id",
                CommitFileSide.AFTER,
            )

            val restored = NoteFile.parse(draft.readText())
            assertEquals("draft-id", restored.id)
            assertNull(restored.plot)
            assertEquals("old body", restored.body)
            assertTrue("historical" in restored.tags)
            assertTrue("Project" in restored.tags)
            assertEquals(listOf("old alias"), restored.aliases)
            assertTrue("custom: old" in draft.readText())
        }
    }

    @Test
    fun fileContentRestoreReappliesTheCurrentProjectTagAfterRename() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply {
                writeText(note("draft-id", "historical body"))
            }
            val service = ProjectCommitService(fileManager)
            val historical = service.commit(PlatformFile(project), "initial")

            val renamedProject = File(project.parentFile, "Renamed Project")
            assertTrue(project.renameTo(renamedProject))
            val renamedDraft = File(renamedProject, draft.name)
            renamedDraft.writeText(note("draft-id", "current body"))
            service.commit(PlatformFile(renamedProject), "after project rename")

            service.restoreFileContent(
                project = PlatformFile(renamedProject),
                commitId = historical.commit.id,
                fileId = "draft-id",
                side = CommitFileSide.AFTER,
            )

            val restored = NoteFile.parse(renamedDraft.readText())
            assertEquals("historical body", restored.body)
            assertTrue("Renamed_Project" in restored.tags)
        }
    }

    @Test
    fun fileContentRestoreDoesNotReintroduceAnotherFoldersManagedTag() = runBlocking {
        withProject { project, fileManager ->
            val concept = File(project, "1. Concept").apply { mkdirs() }
            val outline = File(project, "2. Outline").apply { mkdirs() }
            assertTrue(
                fileManager.writeConfig(
                    ProjectConfig(
                        folders = mapOf(
                            "1. Concept" to FolderConfig(
                                type = FolderType.DEFAULT,
                                autoTags = listOf("concept_tag"),
                            ),
                            "2. Outline" to FolderConfig(
                                type = FolderType.DEFAULT,
                                autoTags = listOf("outline_tag"),
                            ),
                        ),
                    ),
                ) != null,
            )
            val historicalFile = File(concept, "Draft.md").apply {
                writeText(
                    richNote(
                        id = "draft-id",
                        tags = listOf("Project", "concept_tag", "historical_user_tag"),
                        aliases = emptyList(),
                        plot = null,
                        custom = "historical",
                        body = "historical body",
                    ),
                )
            }
            val service = ProjectCommitService(fileManager)
            val historical = service.commit(PlatformFile(project), "concept")

            val currentFile = File(outline, "Draft.md")
            assertTrue(historicalFile.renameTo(currentFile))
            currentFile.writeText(
                richNote(
                    id = "draft-id",
                    tags = listOf("Project", "outline_tag", "current_user_tag"),
                    aliases = emptyList(),
                    plot = null,
                    custom = "current",
                    body = "current body",
                ),
            )
            service.commit(PlatformFile(project), "outline")

            service.restoreFileContent(
                project = PlatformFile(project),
                commitId = historical.commit.id,
                fileId = "draft-id",
                side = CommitFileSide.AFTER,
            )

            val restored = NoteFile.parse(currentFile.readText())
            assertTrue("outline_tag" in restored.tags)
            assertFalse("concept_tag" in restored.tags)
            assertTrue("historical_user_tag" in restored.tags)
            assertTrue("current_user_tag" in restored.tags)
        }
    }

    @Test
    fun projectSnapshotRestoreNormalizesHistoricalFilesToTheCurrentProjectTag() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply {
                writeText(note("draft-id", "historical body"))
            }
            val service = ProjectCommitService(fileManager)
            val historical = service.commit(PlatformFile(project), "initial")

            val renamedProject = File(project.parentFile, "Renamed Project")
            assertTrue(project.renameTo(renamedProject))
            val renamedDraft = File(renamedProject, draft.name)
            renamedDraft.writeText(note("draft-id", "current body"))
            service.commit(PlatformFile(renamedProject), "after project rename")

            val result = service.restore(
                project = PlatformFile(renamedProject),
                commitId = historical.commit.id,
            )

            val restored = NoteFile.parse(renamedDraft.readText())
            assertEquals("historical body", restored.body)
            assertTrue("Renamed_Project" in restored.tags)
            assertEquals(
                result.workingTreeHash,
                service.preview(PlatformFile(renamedProject)).workingTreeHash,
            )
        }
    }

    @Test
    fun fileRestoreAllowsUnrelatedDirtyFileAndMovesOnlySelectedIdentity() = runBlocking {
        withProject { project, fileManager ->
            val originalFolder = File(project, "1. Concept").apply { mkdirs() }
            val currentFolder = File(project, "2. Outline").apply { mkdirs() }
            val selected = File(originalFolder, "1. Original.md").apply {
                writeText(note("selected-id", "historical"))
            }
            val unrelated = File(project, "Unrelated.md").apply {
                writeText(note("unrelated-id", "clean"))
            }
            val service = ProjectCommitService(fileManager)
            val first = service.commit(PlatformFile(project), "initial")

            val moved = File(currentFolder, "8. Current.md")
            assertTrue(selected.renameTo(moved))
            moved.writeText(note("selected-id", "current"))
            service.commit(PlatformFile(project), "move selected")
            unrelated.writeText(note("unrelated-id", "dirty unrelated"))

            val result = service.restoreFile(
                PlatformFile(project),
                first.commit.id,
                "selected-id",
                CommitFileSide.AFTER,
            )

            assertTrue(result.changed)
            assertEquals("2. Outline/8. Current.md", result.previousPath)
            assertEquals("1. Concept/1. Original.md", result.restoredPath)
            assertFalse(moved.exists())
            val restored = File(originalFolder, "1. Original.md")
            assertTrue(restored.isFile)
            assertEquals("historical", NoteFile.parse(restored.readText()).body)
            assertEquals("dirty unrelated", NoteFile.parse(unrelated.readText()).body)
            assertEquals(
                setOf("selected-id", "unrelated-id"),
                service.preview(PlatformFile(project)).changes.mapTo(mutableSetOf(), CommitChange::fileId),
            )
        }
    }

    @Test
    fun fileRestoreRecreatesCommittedDeletionAndDeletesCommittedAddition() = runBlocking {
        withProject { project, fileManager ->
            val folder = File(project, "1. Concept").apply { mkdirs() }
            val deleted = File(folder, "1. Deleted.md").apply {
                writeText(note("deleted-id", "before deletion"))
            }
            val service = ProjectCommitService(fileManager)
            service.commit(PlatformFile(project), "initial")
            assertTrue(deleted.delete())
            val deletionCommit = service.commit(PlatformFile(project), "delete")

            val recreated = service.restoreFile(
                PlatformFile(project),
                deletionCommit.commit.id,
                "deleted-id",
                CommitFileSide.BEFORE,
            )
            assertTrue(recreated.changed)
            assertNull(recreated.previousPath)
            assertEquals("1. Concept/1. Deleted.md", recreated.restoredPath)
            assertEquals("before deletion", NoteFile.parse(deleted.readText()).body)

            service.commit(PlatformFile(project), "restore deleted")
            val added = File(project, "Added.md").apply { writeText(note("added-id", "added")) }
            val additionCommit = service.commit(PlatformFile(project), "add")

            val removed = service.restoreFile(
                PlatformFile(project),
                additionCommit.commit.id,
                "added-id",
                CommitFileSide.BEFORE,
            )
            assertTrue(removed.changed)
            assertEquals("Added.md", removed.previousPath)
            assertNull(removed.restoredPath)
            assertFalse(added.exists())
            assertTrue(folder.isDirectory)
        }
    }

    @Test
    fun fileRestoreRejectsDestinationOwnedByAnotherIdentityWithoutChangingFiles() = runBlocking {
        withProject { project, fileManager ->
            val originalFolder = File(project, "1. Concept").apply { mkdirs() }
            val currentFolder = File(project, "2. Outline").apply { mkdirs() }
            val original = File(originalFolder, "Draft.md").apply {
                writeText(note("selected-id", "selected historical"))
            }
            val service = ProjectCommitService(fileManager)
            val first = service.commit(PlatformFile(project), "initial")

            val moved = File(currentFolder, "Draft.md")
            assertTrue(original.renameTo(moved))
            val conflicting = File(originalFolder, "Draft.md").apply {
                writeText(note("conflict-id", "conflicting"))
            }
            service.commit(PlatformFile(project), "move and occupy old path")

            assertFailsWith<CommitConflictException> {
                service.restoreFile(
                    PlatformFile(project),
                    first.commit.id,
                    "selected-id",
                    CommitFileSide.AFTER,
                )
            }
            assertEquals("selected historical", NoteFile.parse(moved.readText()).body)
            assertEquals("conflicting", NoteFile.parse(conflicting.readText()).body)
        }
    }

    @Test
    fun ensureInitialBaselineCreatesAnEmptyBaselineOnlyOnce() = runBlocking {
        withProject { project, fileManager ->
            val service = ProjectCommitService(fileManager)
            val platformProject = PlatformFile(project)

            val first = service.ensureInitialBaseline(platformProject)
            val storeAfterFirst = commitStoreSnapshot(project)
            val second = service.ensureInitialBaseline(platformProject)

            assertEquals(first, second)
            assertEquals(storeAfterFirst, commitStoreSnapshot(project))
            assertEquals(listOf(first.id), service.history(platformProject).map { it.commit.id })
            assertTrue(service.preview(platformProject).changes.isEmpty())
        }
    }

    @Test
    fun ensureInitialBaselineCapturesExistingFilesAndDoesNotOverwriteThemOnSecondCall() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "initial")) }
            val service = ProjectCommitService(fileManager)
            val platformProject = PlatformFile(project)

            val first = service.ensureInitialBaseline(platformProject)
            assertTrue(service.preview(platformProject).changes.isEmpty())
            val storeAfterFirst = commitStoreSnapshot(project)

            draft.writeText(note("draft-id", "local edit"))
            val second = service.ensureInitialBaseline(platformProject)

            assertEquals(first, second)
            assertEquals("local edit", NoteFile.parse(draft.readText()).body)
            assertEquals(storeAfterFirst, commitStoreSnapshot(project))
            assertEquals(CommitChangeKind.MODIFIED, service.preview(platformProject).changes.single().kind)
            assertEquals(listOf(first.id), service.history(platformProject).map { it.commit.id })
        }
    }

    @Test
    fun restoreHeadSnapshotDiscardsDirtyChangesAboveTheInitialBaseline() = runBlocking {
        withProject { project, fileManager ->
            val baselineContent = rawFormattedNote(
                id = "draft-id",
                tagsAsFlow = true,
                body = "baseline",
                projectTagLast = true,
            )
            val draft = File(project, "Draft.md").apply { writeText(baselineContent) }
            val service = ProjectCommitService(fileManager)
            val platformProject = PlatformFile(project)
            val baseline = service.ensureInitialBaseline(platformProject)
            assertContentEquals(baselineContent.toByteArray(), draft.readBytes())
            draft.writeText(
                rawFormattedNote(
                    id = "draft-id",
                    tagsAsFlow = false,
                    body = "uncommitted edit",
                ),
            )
            val dirtyPreview = service.preview(platformProject)

            val result = service.restoreHeadSnapshot(
                project = platformProject,
                commitId = baseline.id,
                expectedWorkingTreeHash = dirtyPreview.workingTreeHash,
            )

            assertContentEquals(baselineContent.toByteArray(), draft.readBytes())
            val restoredPreview = service.preview(platformProject)
            assertFalse(restoredPreview.hasChanges)
            assertEquals(baseline.id, service.history(platformProject).single().commit.id)
            assertEquals(result.workingTreeHash, restoredPreview.workingTreeHash)
        }
    }

    @Test
    fun restoreHeadSnapshotReturnsToHeadAfterAnOlderSnapshotRestore() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val service = ProjectCommitService(fileManager)
            val platformProject = PlatformFile(project)
            val first = service.commit(platformProject, "first")
            draft.writeText(note("draft-id", "head"))
            val head = service.commit(platformProject, "head")

            service.restore(platformProject, first.commit.id)
            val restoredPreview = service.preview(platformProject)
            val result = service.restoreHeadSnapshot(
                project = platformProject,
                commitId = head.commit.id,
                expectedWorkingTreeHash = restoredPreview.workingTreeHash,
            )

            assertEquals("head", NoteFile.parse(draft.readText()).body)
            assertFalse(service.preview(platformProject).hasChanges)
            assertEquals(head.commit.id, service.history(platformProject).first().commit.id)
            assertEquals(result.workingTreeHash, service.preview(platformProject).workingTreeHash)
        }
    }

    @Test
    fun revertHeadAppliesItsParentTreeWithoutMovingHeadOrChangingCommitStore() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val service = ProjectCommitService(fileManager)
            val platformProject = PlatformFile(project)
            val first = service.commit(platformProject, "first")

            draft.writeText(note("draft-id", "second"))
            val added = File(project, "Added.md").apply { writeText(note("added-id", "added")) }
            val second = service.commit(platformProject, "second")
            val historyBefore = service.history(platformProject).map { it.commit.id }
            val storeBefore = commitStoreSnapshot(project)

            val result = service.revertHead(platformProject, second.commit.id)

            assertEquals(first.commit.id, result.targetCommit.id)
            assertTrue(result.workingTreeHash.isNotBlank())
            assertEquals("first", NoteFile.parse(draft.readText()).body)
            assertFalse(added.exists())
            assertEquals(historyBefore, service.history(platformProject).map { it.commit.id })
            assertEquals(second.commit.id, service.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun restoreHashAllowsSequentialHistoricalRestoresAndRejectsAStaleHash() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val service = ProjectCommitService(fileManager)
            val platformProject = PlatformFile(project)
            val first = service.commit(platformProject, "first")
            draft.writeText(note("draft-id", "second"))
            val second = service.commit(platformProject, "second")
            draft.writeText(note("draft-id", "third"))
            val third = service.commit(platformProject, "third")
            val historyBefore = service.history(platformProject).map { it.commit.id }
            val storeBefore = commitStoreSnapshot(project)

            val restoredFirst = service.restore(platformProject, first.commit.id)
            assertEquals("first", NoteFile.parse(draft.readText()).body)

            val restoredSecond = service.restore(
                project = platformProject,
                commitId = second.commit.id,
                expectedWorkingTreeHash = restoredFirst.workingTreeHash,
            )
            assertEquals("second", NoteFile.parse(draft.readText()).body)
            assertNotEquals(restoredFirst.workingTreeHash, restoredSecond.workingTreeHash)

            val restoredHead = service.restore(
                project = platformProject,
                commitId = third.commit.id,
                expectedWorkingTreeHash = restoredSecond.workingTreeHash,
            )
            assertEquals("third", NoteFile.parse(draft.readText()).body)
            assertFalse(service.preview(platformProject).hasChanges)
            assertEquals(third.commit.id, restoredHead.targetCommit.id)

            assertFailsWith<UncommittedChangesException> {
                service.restore(
                    project = platformProject,
                    commitId = first.commit.id,
                    expectedWorkingTreeHash = restoredFirst.workingTreeHash,
                )
            }
            assertEquals("third", NoteFile.parse(draft.readText()).body)
            assertEquals(historyBefore, service.history(platformProject).map { it.commit.id })
            assertEquals(third.commit.id, service.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun projectRestoreRollsBackPartiallyAppliedFilesWhenSecondWriteFails() = runBlocking {
        withProject { project, fileManager ->
            val firstFile = File(project, "A.md").apply {
                writeText(rawFormattedNote("a-id", tagsAsFlow = true, body = "a historical"))
            }
            val secondFile = File(project, "B.md").apply {
                writeText(rawFormattedNote("b-id", tagsAsFlow = false, body = "b historical"))
            }
            val unrelated = File(project, "Unrelated.md").apply {
                writeText(note("z-unrelated-id", "unchanged"))
            }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")

            firstFile.writeText(rawFormattedNote("a-id", tagsAsFlow = false, body = "a current"))
            secondFile.writeText(rawFormattedNote("b-id", tagsAsFlow = true, body = "b current"))
            val head = normalService.commit(platformProject, "current")
            val firstBefore = firstFile.readBytes()
            val secondBefore = secondFile.readBytes()
            val unrelatedBefore = unrelated.readBytes()
            val historyBefore = normalService.history(platformProject).map { it.commit.id }
            val storeBefore = commitStoreSnapshot(project)
            val failingService = ProjectCommitService(
                fileManager = fileManager,
                workspaceMutator = FaultInjectingCommitWorkspaceMutator(
                    fileManager = fileManager,
                    failWriteCall = 2,
                ),
            )

            assertFailsWith<CommitStorageException> {
                failingService.restore(platformProject, historical.commit.id)
            }

            assertContentEquals(firstBefore, firstFile.readBytes())
            assertContentEquals(secondBefore, secondFile.readBytes())
            assertContentEquals(unrelatedBefore, unrelated.readBytes())
            assertEquals(historyBefore, normalService.history(platformProject).map { it.commit.id })
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun projectRestoreCancellationAfterCreatingAnEmptyFileRestoresTheOriginalPathAndRawTree() = runBlocking {
        withProject { project, fileManager ->
            val historicalFolder = File(project, "1. Concept").apply { mkdirs() }
            val currentFolder = File(project, "2. Outline").apply { mkdirs() }
            val historicalFile = File(historicalFolder, "Historical.md").apply {
                writeText(rawFormattedNote("draft-id", tagsAsFlow = true, body = "historical"))
            }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")

            val currentFile = File(currentFolder, "Current.md")
            assertTrue(historicalFile.renameTo(currentFile))
            currentFile.writeText(rawFormattedNote("draft-id", tagsAsFlow = false, body = "current"))
            val head = normalService.commit(platformProject, "current")
            val currentBefore = currentFile.readBytes()
            val previewBefore = normalService.preview(platformProject)
            val historyBefore = normalService.history(platformProject).map { it.commit.id }
            val storeBefore = commitStoreSnapshot(project)
            val mutator = PausingCommitWorkspaceMutator(
                fileManager = fileManager,
                checkpoint = MutationCheckpoint.CREATE_FILE,
            )
            val service = ProjectCommitService(fileManager, mutator)

            val error = cancelAtMutationCheckpoint(mutator) {
                service.restore(platformProject, historical.commit.id)
            }

            assertTrue(error is CancellationException)
            assertFalse(historicalFile.exists())
            assertTrue(currentFile.isFile)
            assertContentEquals(currentBefore, currentFile.readBytes())
            val previewAfter = normalService.preview(platformProject)
            assertFalse(previewAfter.hasChanges)
            assertEquals(previewBefore.workingTreeHash, previewAfter.workingTreeHash)
            assertEquals(historyBefore, normalService.history(platformProject).map { it.commit.id })
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun projectRestoreTruncatedWriteFailureRestoresTheOriginalRawTreeAndHead() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply {
                writeText(rawFormattedNote("draft-id", tagsAsFlow = true, body = "historical"))
            }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")

            draft.writeText(rawFormattedNote("draft-id", tagsAsFlow = false, body = "current"))
            val head = normalService.commit(platformProject, "current")
            val draftBefore = draft.readBytes()
            val previewBefore = normalService.preview(platformProject)
            val historyBefore = normalService.history(platformProject).map { it.commit.id }
            val storeBefore = commitStoreSnapshot(project)
            val failingService = ProjectCommitService(
                fileManager = fileManager,
                workspaceMutator = FaultInjectingCommitWorkspaceMutator(
                    fileManager = fileManager,
                    truncateWriteCall = 1,
                ),
            )

            val error = assertFailsWith<CommitStorageException> {
                failingService.restore(platformProject, historical.commit.id)
            }

            assertFalse(error is RestoreRollbackFailedException)
            assertTrue(draft.isFile)
            assertContentEquals(draftBefore, draft.readBytes())
            val previewAfter = normalService.preview(platformProject)
            assertFalse(previewAfter.hasChanges)
            assertEquals(previewBefore.workingTreeHash, previewAfter.workingTreeHash)
            assertEquals(historyBefore, normalService.history(platformProject).map { it.commit.id })
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun fileContentRestoreRollsBackOriginalTextWhenWriteFails() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "historical")) }
            val unrelated = File(project, "Unrelated.md").apply {
                writeText(note("unrelated-id", "committed"))
            }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")
            draft.writeText(note("draft-id", "current"))
            val head = normalService.commit(platformProject, "current")
            val unrelatedBefore = NoteFile.parse(note("unrelated-id", "dirty unrelated"))
                .withTags(listOf("Project"))
                .inject()
            unrelated.writeText(unrelatedBefore)
            assertTrue(unrelated.setLastModified(1_234_000L))
            val unrelatedModifiedBefore = unrelated.lastModified()
            val draftBefore = draft.readText()
            val historyBefore = normalService.history(platformProject).map { it.commit.id }
            val storeBefore = commitStoreSnapshot(project)
            val failingService = ProjectCommitService(
                fileManager = fileManager,
                workspaceMutator = FaultInjectingCommitWorkspaceMutator(
                    fileManager = fileManager,
                    failWriteCall = 1,
                ),
            )

            assertFailsWith<CommitStorageException> {
                failingService.restoreFileContent(
                    project = platformProject,
                    commitId = historical.commit.id,
                    fileId = "draft-id",
                    side = CommitFileSide.AFTER,
                )
            }

            assertEquals(draftBefore, draft.readText())
            assertEquals(unrelatedBefore, unrelated.readText())
            assertEquals(unrelatedModifiedBefore, unrelated.lastModified())
            assertEquals(historyBefore, normalService.history(platformProject).map { it.commit.id })
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun movingFileRestoreCleansNewDestinationAndKeepsOriginalWhenDeleteFails() = runBlocking {
        withProject { project, fileManager ->
            val historicalFolder = File(project, "1. Concept").apply { mkdirs() }
            val currentFolder = File(project, "2. Outline").apply { mkdirs() }
            val historicalFile = File(historicalFolder, "Draft.md").apply {
                writeText(note("draft-id", "historical"))
            }
            val unrelated = File(project, "Unrelated.md").apply {
                writeText(note("unrelated-id", "unchanged"))
            }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")
            val currentFile = File(currentFolder, "Current.md")
            assertTrue(historicalFile.renameTo(currentFile))
            currentFile.writeText(note("draft-id", "current"))
            val head = normalService.commit(platformProject, "current")
            val currentBefore = currentFile.readText()
            val unrelatedBefore = unrelated.readText()
            assertTrue(unrelated.setLastModified(1_234_000L))
            val unrelatedModifiedBefore = unrelated.lastModified()
            val historyBefore = normalService.history(platformProject).map { it.commit.id }
            val storeBefore = commitStoreSnapshot(project)
            val failingService = ProjectCommitService(
                fileManager = fileManager,
                workspaceMutator = FaultInjectingCommitWorkspaceMutator(
                    fileManager = fileManager,
                    failDeleteCall = 1,
                ),
            )

            assertFailsWith<CommitStorageException> {
                failingService.restoreFile(
                    project = platformProject,
                    commitId = historical.commit.id,
                    fileId = "draft-id",
                    side = CommitFileSide.AFTER,
                )
            }

            assertFalse(historicalFile.exists())
            assertTrue(currentFile.isFile)
            assertEquals(currentBefore, currentFile.readText())
            assertEquals(unrelatedBefore, unrelated.readText())
            assertEquals(unrelatedModifiedBefore, unrelated.lastModified())
            assertEquals(historyBefore, normalService.history(platformProject).map { it.commit.id })
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun fileContentRestoreRollsBackWhenJobIsCancelledAfterWrite() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "historical")) }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")
            draft.writeText(note("draft-id", "current"))
            val head = normalService.commit(platformProject, "current")
            val original = draft.readText()
            val storeBefore = commitStoreSnapshot(project)
            val mutator = PausingCommitWorkspaceMutator(
                fileManager = fileManager,
                checkpoint = MutationCheckpoint.WRITE,
            )
            val service = ProjectCommitService(fileManager, mutator)

            val error = cancelAtMutationCheckpoint(mutator) {
                service.restoreFileContent(
                    project = platformProject,
                    commitId = historical.commit.id,
                    fileId = "draft-id",
                    side = CommitFileSide.AFTER,
                )
            }

            assertTrue(error is CancellationException)
            assertEquals(original, draft.readText())
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun movingFileRestoreRollsBackWhenJobIsCancelledAfterDestinationCreation() = runBlocking {
        assertMovingFileRestoreCancellation(MutationCheckpoint.CREATE_FILE)
    }

    @Test
    fun movingFileRestoreRollsBackWhenJobIsCancelledAfterDestinationWrite() = runBlocking {
        assertMovingFileRestoreCancellation(MutationCheckpoint.WRITE)
    }

    @Test
    fun movingFileRestoreRollsBackWhenJobIsCancelledAfterSourceDeletion() = runBlocking {
        assertMovingFileRestoreCancellation(MutationCheckpoint.DELETE)
    }

    @Test
    fun projectRestoreRollsBackPartiallyAppliedFilesWhenJobIsCancelled() = runBlocking {
        withProject { project, fileManager ->
            val firstFile = File(project, "A.md").apply { writeText(note("a-id", "a historical")) }
            val secondFile = File(project, "B.md").apply { writeText(note("b-id", "b historical")) }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")
            firstFile.writeText(note("a-id", "a current"))
            secondFile.writeText(note("b-id", "b current"))
            val head = normalService.commit(platformProject, "current")
            val firstBefore = firstFile.readText()
            val secondBefore = secondFile.readText()
            val storeBefore = commitStoreSnapshot(project)
            val mutator = PausingCommitWorkspaceMutator(
                fileManager = fileManager,
                checkpoint = MutationCheckpoint.WRITE,
            )
            val service = ProjectCommitService(fileManager, mutator)

            val error = cancelAtMutationCheckpoint(mutator) {
                service.restore(platformProject, historical.commit.id)
            }

            assertTrue(error is CancellationException)
            assertEquals(firstBefore, firstFile.readText())
            assertEquals(secondBefore, secondFile.readText())
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    @Test
    fun cancelledFileRestoreReportsRollbackFailureAndKeepsHead() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "historical")) }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")
            draft.writeText(note("draft-id", "current"))
            val head = normalService.commit(platformProject, "current")
            val storeBefore = commitStoreSnapshot(project)
            val mutator = PausingCommitWorkspaceMutator(
                fileManager = fileManager,
                checkpoint = MutationCheckpoint.WRITE,
                failBeforeWriteCall = 2,
            )
            val service = ProjectCommitService(fileManager, mutator)

            val error = cancelAtMutationCheckpoint(mutator) {
                service.restoreFileContent(
                    project = platformProject,
                    commitId = historical.commit.id,
                    fileId = "draft-id",
                    side = CommitFileSide.AFTER,
                )
            }

            assertTrue(error is RestoreRollbackFailedException)
            assertTrue(
                generateSequence(error.cause) { cause -> cause.cause }
                    .any { cause -> cause is CancellationException },
            )
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { cause ->
                cause.suppressed.any { it.message == "injected rollback write failure" }
            })
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    private suspend fun assertMovingFileRestoreCancellation(checkpoint: MutationCheckpoint) {
        withProject { project, fileManager ->
            val historicalFolder = File(project, "1. Concept").apply { mkdirs() }
            val currentFolder = File(project, "2. Outline").apply { mkdirs() }
            val historicalFile = File(historicalFolder, "Draft.md").apply {
                writeText(note("draft-id", "historical"))
            }
            val platformProject = PlatformFile(project)
            val normalService = ProjectCommitService(fileManager)
            val historical = normalService.commit(platformProject, "historical")
            val currentFile = File(currentFolder, "Current.md")
            assertTrue(historicalFile.renameTo(currentFile))
            currentFile.writeText(note("draft-id", "current"))
            val head = normalService.commit(platformProject, "current")
            val currentBefore = currentFile.readText()
            val storeBefore = commitStoreSnapshot(project)
            val mutator = PausingCommitWorkspaceMutator(fileManager, checkpoint)
            val service = ProjectCommitService(fileManager, mutator)

            val error = cancelAtMutationCheckpoint(mutator) {
                service.restoreFile(
                    project = platformProject,
                    commitId = historical.commit.id,
                    fileId = "draft-id",
                    side = CommitFileSide.AFTER,
                )
            }

            assertTrue(error is CancellationException)
            assertFalse(historicalFile.exists())
            assertTrue(currentFile.isFile)
            assertEquals(currentBefore, currentFile.readText())
            assertEquals(head.commit.id, normalService.history(platformProject).first().commit.id)
            assertEquals(storeBefore, commitStoreSnapshot(project))
        }
    }

    private suspend fun cancelAtMutationCheckpoint(
        mutator: PausingCommitWorkspaceMutator,
        operation: suspend () -> Unit,
    ): Throwable = supervisorScope {
        val outcome = CompletableDeferred<Result<Unit>>()
        val job = launch {
            outcome.complete(runCatching { operation() })
        }
        withTimeout(5.seconds) { mutator.awaitCheckpoint() }
        job.cancel(CancellationException("cancel restore transaction"))
        mutator.releaseCheckpoint()
        val error = withTimeout(5.seconds) { outcome.await() }.exceptionOrNull()
            ?: error("cancelled restore unexpectedly succeeded")
        job.join()
        assertTrue(job.isCancelled)
        error
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

    private fun commitStoreSnapshot(project: File): Map<String, List<Byte>> {
        val store = File(project, ".machum")
        return store.walkTopDown()
            .filter(File::isFile)
            .associate { file ->
                file.relativeTo(store).invariantSeparatorsPath to file.readBytes().toList()
            }
    }

    private class FaultInjectingCommitWorkspaceMutator(
        private val fileManager: FileManager,
        private val failWriteCall: Int? = null,
        private val truncateWriteCall: Int? = null,
        private val failDeleteCall: Int? = null,
    ) : CommitWorkspaceMutator {
        private var writeCalls = 0
        private var deleteCalls = 0

        override suspend fun createFolder(
            parentDirectory: PlatformFile,
            name: String,
        ): PlatformFile? = fileManager.createFolder(parentDirectory, name)

        override suspend fun createFile(
            parentDirectory: PlatformFile,
            name: String,
            mimeType: String,
        ): PlatformFile? = fileManager.createCommitStorageFile(parentDirectory, name, mimeType)

        override suspend fun write(file: PlatformFile, content: String) {
            writeCalls += 1
            if (writeCalls == truncateWriteCall) {
                fileManager.write(file, "")
                error("injected truncated write failure")
            }
            fileManager.write(file, content)
            if (writeCalls == failWriteCall) error("injected write failure")
        }

        override suspend fun delete(file: PlatformFile): Boolean {
            deleteCalls += 1
            if (deleteCalls == failDeleteCall) return false
            return fileManager.delete(file)
        }
    }

    private enum class MutationCheckpoint {
        CREATE_FILE,
        WRITE,
        DELETE,
    }

    private class PausingCommitWorkspaceMutator(
        private val fileManager: FileManager,
        private val checkpoint: MutationCheckpoint,
        private val checkpointCall: Int = 1,
        private val failBeforeWriteCall: Int? = null,
    ) : CommitWorkspaceMutator {
        private val checkpointReached = CompletableDeferred<Unit>()
        private val checkpointRelease = CompletableDeferred<Unit>()
        private var createFileCalls = 0
        private var writeCalls = 0
        private var deleteCalls = 0
        private var didPause = false

        suspend fun awaitCheckpoint() = checkpointReached.await()

        fun releaseCheckpoint() {
            checkpointRelease.complete(Unit)
        }

        override suspend fun createFolder(
            parentDirectory: PlatformFile,
            name: String,
        ): PlatformFile? = fileManager.createFolder(parentDirectory, name)

        override suspend fun createFile(
            parentDirectory: PlatformFile,
            name: String,
            mimeType: String,
        ): PlatformFile? {
            createFileCalls += 1
            val created = fileManager.createCommitStorageFile(parentDirectory, name, mimeType)
            pauseIfNeeded(MutationCheckpoint.CREATE_FILE, createFileCalls)
            return created
        }

        override suspend fun write(file: PlatformFile, content: String) {
            writeCalls += 1
            if (writeCalls == failBeforeWriteCall) error("injected rollback write failure")
            fileManager.write(file, content)
            pauseIfNeeded(MutationCheckpoint.WRITE, writeCalls)
        }

        override suspend fun delete(file: PlatformFile): Boolean {
            deleteCalls += 1
            val deleted = fileManager.delete(file)
            pauseIfNeeded(MutationCheckpoint.DELETE, deleteCalls)
            return deleted
        }

        private suspend fun pauseIfNeeded(actual: MutationCheckpoint, call: Int) {
            if (didPause || actual != checkpoint || call != checkpointCall) return
            didPause = true
            checkpointReached.complete(Unit)
            checkpointRelease.await()
        }
    }

    private fun note(id: String, body: String): String = """
        ---
        id: $id
        tags:
          - Project
        ---

        $body
    """.trimIndent()

    private fun rawFormattedNote(
        id: String,
        tagsAsFlow: Boolean,
        body: String,
        projectTagLast: Boolean = false,
    ): String = buildString {
        append("\uFEFF---\r\n")
        append("# exact formatting must survive commit scans\r\n")
        append("id: '").append(id).append("'\r\n")
        if (tagsAsFlow) {
            if (projectTagLast) append("tags: [manual, Project]\r\n")
            else append("tags: [Project, manual]\r\n")
        } else {
            if (projectTagLast) append("tags:\r\n  - manual\r\n  - Project\r\n")
            else append("tags:\r\n  - Project\r\n  - manual\r\n")
        }
        append("custom:  keep # spacing\r\n")
        append("---\r\n\r\n")
        append(body)
        append("\r\n")
    }

    private fun richNote(
        id: String,
        tags: List<String>,
        aliases: List<String>,
        plot: String?,
        custom: String,
        body: String,
    ): String = buildString {
        appendLine("---")
        appendLine("id: $id")
        appendLine("tags:")
        tags.forEach { appendLine("  - $it") }
        appendLine("aliases:")
        aliases.forEach { appendLine("  - $it") }
        if (plot != null) appendLine("plot: $plot")
        appendLine("custom: $custom")
        appendLine("---")
        appendLine()
        append(body)
    }
}
