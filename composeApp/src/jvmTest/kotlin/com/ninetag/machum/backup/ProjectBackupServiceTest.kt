package com.ninetag.machum.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.commit.ProjectCommitService
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ProjectBackupServiceTest {
    @Test
    fun initialBackupPublishesReachableHistoryAndCurrentWorkspace() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val commitService = ProjectCommitService(fileManager)
            val first = commitService.commit(PlatformFile(project), "initial")

            draft.writeText(note("draft-id", "second"))
            File(project, "Character.md").writeText(note("character-id", "hero"))
            val second = commitService.commit(PlatformFile(project), "expand")
            val remote = FakeRemoteProjectBackupStore()

            val result = ProjectBackupService(fileManager, remote).backup(
                project = PlatformFile(project),
                targetCommitId = second.commit.id,
            )

            assertEquals(second.commit.id, remote.headCommitId)
            assertEquals(2, remote.objectsOf(ImmutableBackupObjectKind.COMMIT).size)
            assertEquals(2, remote.objectsOf(ImmutableBackupObjectKind.TREE).size)
            assertEquals(3, remote.objectsOf(ImmutableBackupObjectKind.BLOB).size)
            assertEquals(
                listOf("Character.md", "Draft.md"),
                remote.workspace.map(BackupWorkspaceEntry::relativePath),
            )
            assertFalse(remote.workspace.any { it.relativePath == ".machum.json" })
            assertEquals(
                listOf("metadata/repository.json", "metadata/project-config.json"),
                remote.metadata.map(MutableBackupObject::relativePath),
            )
            assertEquals(7, result.createdObjectCount)
            assertEquals(2, result.metadataFileCount)
            assertEquals(2, result.workspaceFileCount)
            assertEquals(false, "head:${first.commit.id}" in remote.events)
            assertTrue(remote.events.last().startsWith("head:${second.commit.id}"))
        }
    }

    @Test
    fun incrementalBackupReusesRenameBlobAndMirrorsAddModifyDelete() = runBlocking {
        withProject { project, fileManager ->
            val renamed = File(project, "Draft.md").apply { writeText(note("draft-id", "same")) }
            val modified = File(project, "Outline.md").apply { writeText(note("outline-id", "old")) }
            val deleted = File(project, "Delete.md").apply { writeText(note("delete-id", "gone")) }
            val commitService = ProjectCommitService(fileManager)
            val first = commitService.commit(PlatformFile(project), "initial")
            val remote = FakeRemoteProjectBackupStore()
            val backupService = ProjectBackupService(fileManager, remote)
            backupService.backup(PlatformFile(project), first.commit.id)
            val initialBlobCount = remote.objectsOf(ImmutableBackupObjectKind.BLOB).size

            assertTrue(renamed.renameTo(File(project, "Renamed.md")))
            modified.writeText(note("outline-id", "new"))
            assertTrue(deleted.delete())
            File(project, "Added.md").writeText(note("added-id", "added"))
            val second = commitService.commit(PlatformFile(project), "mixed changes")

            val result = backupService.backup(PlatformFile(project), second.commit.id)

            assertEquals(second.commit.id, remote.headCommitId)
            assertEquals(
                listOf("Added.md", "Outline.md", "Renamed.md"),
                remote.workspace.map(BackupWorkspaceEntry::relativePath),
            )
            assertEquals(initialBlobCount + 2, remote.objectsOf(ImmutableBackupObjectKind.BLOB).size)
            assertEquals(4, result.createdObjectCount)
            assertEquals(0, result.reusedObjectCount)
            assertTrue(remote.events.last().startsWith("head:${second.commit.id}"))
        }
    }

    @Test
    fun failedWorkspacePublishLeavesRemoteHeadAndRetryIsIdempotent() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val commitService = ProjectCommitService(fileManager)
            val commit = commitService.commit(PlatformFile(project), "initial")
            val remote = FakeRemoteProjectBackupStore(failWorkspaceOnce = true)
            val backupService = ProjectBackupService(fileManager, remote)

            assertFailsWith<IllegalStateException> {
                backupService.backup(PlatformFile(project), commit.commit.id)
            }
            assertEquals(null, remote.headCommitId)
            assertTrue(remote.immutableObjects.isNotEmpty())
            assertTrue(commitService.preview(PlatformFile(project)).changes.isEmpty())
            assertEquals(commit.commit.id, commitService.history(PlatformFile(project)).single().commit.id)

            val retry = backupService.backup(PlatformFile(project), commit.commit.id)

            assertEquals(0, retry.createdObjectCount)
            assertEquals(remote.immutableObjects.size, retry.reusedObjectCount)
            assertEquals(commit.commit.id, remote.headCommitId)
            assertTrue(remote.events.last().startsWith("head:${commit.commit.id}"))

            val alreadyCurrent = backupService.backup(PlatformFile(project), commit.commit.id)
            assertTrue(alreadyCurrent.alreadyCurrent)
            assertEquals(0, alreadyCurrent.createdObjectCount)
        }
    }

    @Test
    fun failedImmutablePublishNeverAdvancesHeadAndCanResume() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val commitService = ProjectCommitService(fileManager)
            val commit = commitService.commit(PlatformFile(project), "initial")
            val remote = FakeRemoteProjectBackupStore(
                failImmutableKindOnce = ImmutableBackupObjectKind.TREE,
            )
            val backupService = ProjectBackupService(fileManager, remote)

            assertFailsWith<IllegalStateException> {
                backupService.backup(PlatformFile(project), commit.commit.id)
            }
            assertEquals(null, remote.headCommitId)
            assertEquals(1, remote.objectsOf(ImmutableBackupObjectKind.BLOB).size)
            assertTrue(remote.objectsOf(ImmutableBackupObjectKind.TREE).isEmpty())
            assertTrue(remote.workspace.isEmpty())

            val retry = backupService.backup(PlatformFile(project), commit.commit.id)

            assertEquals(2, retry.createdObjectCount)
            assertEquals(1, retry.reusedObjectCount)
            assertEquals(commit.commit.id, remote.headCommitId)
            assertTrue(remote.events.last().startsWith("head:${commit.commit.id}"))
        }
    }

    @Test
    fun backupQueueFailureDoesNotRollBackLocalCommit() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val commitService = ProjectCommitService(fileManager)
            val coordinator = ProjectCommitBackupCoordinator(
                commitService = commitService,
                backupEnqueuer = CommitBackupEnqueuer { _, _ ->
                    error("queue unavailable")
                },
            )

            val result = coordinator.commit(PlatformFile(project), "local first")

            assertEquals(BackupDispatchStatus.FAILED, result.backup.status)
            assertEquals("queue unavailable", result.backup.errorMessage)
            assertEquals(
                result.localCommit.commit.id,
                commitService.history(PlatformFile(project)).single().commit.id,
            )
            assertTrue(commitService.preview(PlatformFile(project)).changes.isEmpty())
            assertTrue(File(project, ".machum/HEAD.json").isFile)
        }
    }

    @Test
    fun unknownRemoteHeadIsRejectedWithoutChangingRemote() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val commitService = ProjectCommitService(fileManager)
            val commit = commitService.commit(PlatformFile(project), "initial")
            val remote = FakeRemoteProjectBackupStore(initialHeadCommitId = "other-history")

            assertFailsWith<BackupHistoryMismatchException> {
                ProjectBackupService(fileManager, remote).backup(
                    PlatformFile(project),
                    commit.commit.id,
                )
            }
            assertEquals("other-history", remote.headCommitId)
            assertTrue(remote.immutableObjects.isEmpty())
        }
    }

    @Test
    fun remoteHeadChangeBeforeMutablePublishLeavesWorkspaceUntouched() = runBlocking {
        withProject { project, fileManager ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val commit = ProjectCommitService(fileManager)
                .commit(PlatformFile(project), "initial")
            val remote = FakeRemoteProjectBackupStore(
                changeHeadOnReadNumber = 3,
                changedHeadCommitId = "external-head",
            )

            assertFailsWith<BackupConcurrencyException> {
                ProjectBackupService(fileManager, remote).backup(
                    PlatformFile(project),
                    commit.commit.id,
                )
            }

            assertEquals("external-head", remote.headCommitId)
            assertTrue(remote.metadata.isEmpty())
            assertTrue(remote.workspace.isEmpty())
            assertFalse(remote.events.any { it.startsWith("head:${commit.commit.id}") })
        }
    }

    @Test
    fun twoServiceInstancesSerializeTheSameProjectBeforeReadingRemoteHead() = runBlocking {
        withProject { project, fileManager ->
            val draft = File(project, "Draft.md").apply { writeText(note("draft-id", "first")) }
            val commitService = ProjectCommitService(fileManager)
            val first = commitService.commit(PlatformFile(project), "first")
            val remote = FakeRemoteProjectBackupStore()
            ProjectBackupService(fileManager, remote).backup(PlatformFile(project), first.commit.id)

            draft.writeText(note("draft-id", "second"))
            val second = commitService.commit(PlatformFile(project), "second")
            draft.writeText(note("draft-id", "third"))
            val third = commitService.commit(PlatformFile(project), "third")
            val gate = ImmutableGate()
            remote.immutableGate = gate
            val firstService = ProjectBackupService(fileManager, remote)
            val secondService = ProjectBackupService(fileManager, remote)

            val firstWork = async(Dispatchers.Default) {
                firstService.backup(PlatformFile(project), second.commit.id)
            }
            gate.entered.await()
            val secondWork = async(Dispatchers.Default) {
                secondService.backup(PlatformFile(project), third.commit.id)
            }

            assertNull(withTimeoutOrNull(200.milliseconds) { gate.readWhileBlocked.await() })
            gate.release.complete(Unit)
            firstWork.await()
            secondWork.await()

            assertEquals(third.commit.id, remote.headCommitId)
            assertEquals(
                listOf(second.commit.id, third.commit.id),
                remote.events
                    .filter { it.startsWith("head:") }
                    .takeLast(2)
                    .map { it.removePrefix("head:") },
            )
        }
    }

    private suspend fun withProject(block: suspend (File, FileManager) -> Unit) {
        val root = withContext(Dispatchers.IO) {
            Files.createTempDirectory("machum-backup")
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

    private fun note(id: String, body: String): String = """
        ---
        id: $id
        ---

        $body
    """.trimIndent()
}

private class FakeRemoteProjectBackupStore(
    initialHeadCommitId: String? = null,
    private var failWorkspaceOnce: Boolean = false,
    private var failImmutableKindOnce: ImmutableBackupObjectKind? = null,
    private val changeHeadOnReadNumber: Int? = null,
    private val changedHeadCommitId: String = "changed-head",
) : RemoteProjectBackupStore {
    val immutableObjects = linkedMapOf<String, ImmutableBackupObject>()
    var metadata: List<MutableBackupObject> = emptyList()
        private set
    var workspace: List<BackupWorkspaceEntry> = emptyList()
        private set
    var headCommitId: String? = initialHeadCommitId
        private set
    val events = mutableListOf<String>()
    var immutableGate: ImmutableGate? = null
    private var headReadCount = 0

    override suspend fun readHeadCommitId(): String? {
        headReadCount += 1
        val gate = immutableGate
        if (gate != null && gate.entered.isCompleted && !gate.release.isCompleted) {
            gate.readWhileBlocked.complete(Unit)
        }
        if (headReadCount == changeHeadOnReadNumber) {
            headCommitId = changedHeadCommitId
        }
        return headCommitId
    }

    override suspend fun putImmutable(
        objectToStore: ImmutableBackupObject,
    ): ImmutablePutResult {
        val gate = immutableGate
        if (gate != null && !gate.entered.isCompleted) {
            gate.entered.complete(Unit)
            gate.release.await()
        }
        if (failImmutableKindOnce == objectToStore.kind) {
            failImmutableKindOnce = null
            error("immutable ${objectToStore.kind} unavailable")
        }
        val existing = immutableObjects[objectToStore.relativePath]
        if (existing != null) {
            check(existing.contentHash == objectToStore.contentHash) {
                "immutable object content changed: ${objectToStore.relativePath}"
            }
            events += "reuse:${objectToStore.relativePath}"
            return ImmutablePutResult.ALREADY_PRESENT
        }
        immutableObjects[objectToStore.relativePath] = objectToStore
        events += "create:${objectToStore.relativePath}"
        return ImmutablePutResult.CREATED
    }

    override suspend fun upsertMetadata(
        targetCommitId: String,
        entries: List<MutableBackupObject>,
    ) {
        metadata = entries
        events += "metadata:$targetCommitId"
    }

    override suspend fun mirrorWorkspace(
        targetCommitId: String,
        entries: List<BackupWorkspaceEntry>,
    ) {
        events += "workspace:$targetCommitId"
        if (failWorkspaceOnce) {
            failWorkspaceOnce = false
            error("workspace unavailable")
        }
        entries.forEach { entry ->
            check(immutableObjects.containsKey("history/blobs/${entry.blobHash}.blob")) {
                "workspace blob is missing: ${entry.blobHash}"
            }
        }
        workspace = entries
    }

    override suspend fun updateHead(
        expectedCommitId: String?,
        head: MutableBackupObject,
    ) {
        if (headCommitId != expectedCommitId) {
            throw BackupConcurrencyException(
                "expected $expectedCommitId but was $headCommitId",
            )
        }
        val commitId = Json.parseToJsonElement(head.content)
            .jsonObject.getValue("commitId")
            .jsonPrimitive.content
        headCommitId = commitId
        events += "head:$commitId"
    }

    fun objectsOf(kind: ImmutableBackupObjectKind): List<ImmutableBackupObject> =
        immutableObjects.values.filter { it.kind == kind }
}

private class ImmutableGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val readWhileBlocked = CompletableDeferred<Unit>()
}
