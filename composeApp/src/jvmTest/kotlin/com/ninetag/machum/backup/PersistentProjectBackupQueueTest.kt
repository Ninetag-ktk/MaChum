package com.ninetag.machum.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ninetag.machum.commit.ProjectCommitService
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PersistentProjectBackupQueueTest {
    @Test
    fun repositoryIdentityIsStableAndNeverBecomesACommitChange() = runBlocking {
        withProject { project, fileManager, _ ->
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val firstStore = ProjectRepositoryIdentityStore(fileManager)
            val first = firstStore.getOrCreate(PlatformFile(project))
            val second = ProjectRepositoryIdentityStore(fileManager)
                .getOrCreate(PlatformFile(project))

            assertEquals(first, second)
            assertTrue(first.projectId.isNotBlank())
            assertTrue(File(project, ".machum/repository.json").isFile)

            val commitService = ProjectCommitService(fileManager)
            commitService.commit(PlatformFile(project), "initial")
            assertTrue(commitService.preview(PlatformFile(project)).changes.isEmpty())
        }
    }

    @Test
    fun queuePersistsBindingCoalescesLatestCommitAndKeepsNewerPendingWork() = runBlocking {
        withProject { project, fileManager, dataStore ->
            val projectFile = PlatformFile(project)
            val identity = ProjectRepositoryIdentityStore(fileManager).getOrCreate(projectFile)
            val queue = PersistentProjectBackupQueue(dataStore) { 100L }
            queue.bind(
                accountId = "google-subject",
                projectId = identity.projectId,
                remoteFolderId = "drive-folder",
            )

            queue.enqueue("google-subject", identity.projectId, projectFile, "commit-1")
            queue.enqueue("google-subject", identity.projectId, projectFile, "commit-2")

            val restored = PersistentProjectBackupQueue(dataStore) { 200L }
            val coalesced = restored.snapshot()
            assertEquals(1, coalesced.pending.size)
            assertEquals("commit-2", coalesced.pending.single().targetCommitId)
            assertEquals(100L, coalesced.pending.single().enqueuedAtEpochMillis)
            assertNotNull(coalesced.pending.single().resolveProject())
            assertEquals("drive-folder", coalesced.bindings.single().remoteFolderId)

            restored.markFailed(
                "google-subject",
                identity.projectId,
                "commit-2",
                "offline",
            )
            assertEquals(1, restored.next()?.attempts)
            assertEquals("offline", restored.next()?.lastErrorMessage)

            restored.enqueue("google-subject", identity.projectId, projectFile, "commit-3")
            restored.markSucceeded("google-subject", identity.projectId, "commit-2")
            assertEquals("commit-3", restored.next()?.targetCommitId)
            assertEquals(
                "commit-2",
                restored.binding("google-subject", identity.projectId)?.lastUploadedCommitId,
            )

            restored.markSucceeded("google-subject", identity.projectId, "commit-3")
            assertTrue(restored.snapshot().pending.isEmpty())
            assertEquals(
                "commit-3",
                restored.binding("google-subject", identity.projectId)?.lastUploadedCommitId,
            )
        }
    }

    @Test
    fun workerKeepsFailedWorkAndANewWorkerCanResumeIt() = runBlocking {
        withProject { project, fileManager, dataStore ->
            val projectFile = PlatformFile(project)
            val identityStore = ProjectRepositoryIdentityStore(fileManager)
            val identity = identityStore.getOrCreate(projectFile)
            val queue = PersistentProjectBackupQueue(dataStore) { 100L }
            queue.bind("google-subject", identity.projectId, "drive-folder")
            File(project, "Draft.md").writeText(note("draft-id", "first"))
            val coordinated = ProjectCommitBackupCoordinator(
                commitService = ProjectCommitService(fileManager),
                backupEnqueuer = PersistentCommitBackupEnqueuer(
                    accountId = "google-subject",
                    identityStore = identityStore,
                    queue = queue,
                ),
            ).commit(projectFile, "initial")
            assertEquals(BackupDispatchStatus.QUEUED, coordinated.backup.status)

            val remote = WorkerRemoteStore(failWorkspaceOnce = true)
            val provider = RemoteProjectBackupStoreProvider { remote }
            val firstWorker = PersistentBackupWorker(fileManager, queue, provider)
            val firstRun = firstWorker.runNext()

            assertIs<BackupWorkResult.Failed>(firstRun)
            assertEquals(1, queue.next()?.attempts)
            assertEquals(null, remote.headCommitId)

            val resumedWorker = PersistentBackupWorker(fileManager, queue, provider)
            val resumed = resumedWorker.runNext()

            val completed = assertIs<BackupWorkResult.Completed>(resumed)
            assertEquals(coordinated.localCommit.commit.id, completed.backup.targetCommitId)
            assertTrue(queue.snapshot().pending.isEmpty())
            assertEquals(
                coordinated.localCommit.commit.id,
                queue.binding("google-subject", identity.projectId)?.lastUploadedCommitId,
            )
            assertEquals(coordinated.localCommit.commit.id, remote.headCommitId)
        }
    }

    private suspend fun withProject(
        block: suspend (File, FileManager, DataStore<Preferences>) -> Unit,
    ) {
        val root = withContext(Dispatchers.IO) {
            Files.createTempDirectory("machum-backup-queue")
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
            block(project, fileManager, dataStore)
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

private class WorkerRemoteStore(
    private var failWorkspaceOnce: Boolean = false,
) : RemoteProjectBackupStore {
    private val immutableObjects = linkedMapOf<String, ImmutableBackupObject>()
    var headCommitId: String? = null
        private set

    override suspend fun readHeadCommitId(): String? = headCommitId

    override suspend fun putImmutable(
        objectToStore: ImmutableBackupObject,
    ): ImmutablePutResult {
        val existing = immutableObjects[objectToStore.relativePath]
        if (existing != null) {
            check(existing.contentHash == objectToStore.contentHash)
            return ImmutablePutResult.ALREADY_PRESENT
        }
        immutableObjects[objectToStore.relativePath] = objectToStore
        return ImmutablePutResult.CREATED
    }

    override suspend fun upsertMetadata(
        targetCommitId: String,
        entries: List<MutableBackupObject>,
    ) = Unit

    override suspend fun mirrorWorkspace(
        targetCommitId: String,
        entries: List<BackupWorkspaceEntry>,
    ) {
        if (failWorkspaceOnce) {
            failWorkspaceOnce = false
            error("workspace unavailable")
        }
        entries.forEach { entry ->
            check(immutableObjects.containsKey("history/blobs/${entry.blobHash}.blob"))
        }
    }

    override suspend fun updateHead(
        expectedCommitId: String?,
        head: MutableBackupObject,
    ) {
        check(headCommitId == expectedCommitId)
        headCommitId = Json.parseToJsonElement(head.content)
            .jsonObject.getValue("commitId")
            .jsonPrimitive.content
    }
}
