package com.ninetag.machum.external

import com.ninetag.machum.entity.PlotStage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Active-workspace metadata used to build the hierarchy without rereading Markdown bodies.
 *
 * The index is intentionally memory-only and disk contents remain authoritative. When a provider
 * exposes a usable modification time, entries are reused only while it matches.
 * Providers that report no usable time keep successful metadata for the current app session;
 * opening or saving the document refreshes that entry from the actual body.
 */
internal class WorkspaceMetadataIndex {
    private val mutex = Mutex()
    private var workspaceIdentity: WorkspaceMetadataIdentity? = null
    private var activationRevision = 0L
    private var entries: Map<FileKey, WorkspaceFileMetadata> = emptyMap()

    suspend fun activate(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
    ): WorkspaceMetadataSnapshot = mutex.withLock {
        val location = workspace.toString()
        if (workspaceIdentity?.matches(location, workspaceKind) != true) {
            activationRevision += 1
            workspaceIdentity = WorkspaceMetadataIdentity(location, workspaceKind, activationRevision)
            entries = emptyMap()
        }
        WorkspaceMetadataSnapshot(checkNotNull(workspaceIdentity), entries)
    }

    suspend fun deactivate() = mutex.withLock {
        workspaceIdentity = null
        entries = emptyMap()
    }

    /** Read-only snapshot. A stale task cannot reactivate a workspace that has already changed. */
    suspend fun snapshot(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
    ): WorkspaceMetadataSnapshot? = mutex.withLock {
        val identity = workspaceIdentity
            ?.takeIf { it.matches(workspace.toString(), workspaceKind) }
            ?: return@withLock null
        WorkspaceMetadataSnapshot(identity, entries)
    }

    suspend fun reconcileAll(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        baseline: WorkspaceMetadataSnapshot,
        updated: Collection<WorkspaceFileMetadata>,
    ): Map<FileKey, WorkspaceFileMetadata> = mutex.withLock {
        if (!matches(workspace, workspaceKind, baseline)) return@withLock emptyMap()
        val scanned = updated.associateBy(WorkspaceFileMetadata::key)
        entries = reconcile(
            baseline = baseline.entries,
            current = entries,
            scanned = scanned,
            keys = baseline.entries.keys + entries.keys + scanned.keys,
        )
        entries
    }

    suspend fun reconcileFolder(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        folderKey: FolderKey,
        baseline: WorkspaceMetadataSnapshot,
        updated: Collection<WorkspaceFileMetadata>,
    ) = mutex.withLock {
        if (!matches(workspace, workspaceKind, baseline)) return@withLock
        val scanned = updated.associateBy(WorkspaceFileMetadata::key)
        val folderKeys = baseline.entries.keys.filterTo(mutableSetOf()) { it.folder == folderKey }
        folderKeys += entries.keys.filter { it.folder == folderKey }
        folderKeys += scanned.keys
        entries = reconcile(baseline.entries, entries, scanned, folderKeys)
    }

    suspend fun put(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        updated: WorkspaceFileMetadata,
    ) = mutex.withLock {
        if (workspaceIdentity?.matches(workspace.toString(), workspaceKind) != true) return@withLock
        entries = entries + (updated.key to updated)
    }

    suspend fun invalidate(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        keys: Collection<FileKey>,
    ) = mutex.withLock {
        if (workspaceIdentity?.matches(workspace.toString(), workspaceKind) != true) return@withLock
        if (keys.isNotEmpty()) entries = entries - keys.toSet()
    }

    private fun matches(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        baseline: WorkspaceMetadataSnapshot,
    ): Boolean {
        val identity = workspaceIdentity ?: return false
        return identity.matches(workspace.toString(), workspaceKind) &&
            baseline.workspaceIdentity == identity
    }

    /** Keep a concurrently changed key; apply the scan only to keys unchanged since its snapshot. */
    private fun reconcile(
        baseline: Map<FileKey, WorkspaceFileMetadata>,
        current: Map<FileKey, WorkspaceFileMetadata>,
        scanned: Map<FileKey, WorkspaceFileMetadata>,
        keys: Set<FileKey>,
    ): Map<FileKey, WorkspaceFileMetadata> = current.toMutableMap().apply {
        keys.forEach { key ->
            if (current[key] == baseline[key]) {
                scanned[key]?.let { this[key] = it } ?: remove(key)
            }
        }
    }
}

internal data class WorkspaceMetadataSnapshot(
    val workspaceIdentity: WorkspaceMetadataIdentity,
    val entries: Map<FileKey, WorkspaceFileMetadata>,
)

internal data class WorkspaceMetadataIdentity(
    val workspaceLocation: String,
    val workspaceKind: WorkspaceKind,
    val activationRevision: Long,
) {
    fun matches(workspaceLocation: String, workspaceKind: WorkspaceKind): Boolean =
        this.workspaceLocation == workspaceLocation && this.workspaceKind == workspaceKind
}

internal data class WorkspaceFileMetadata(
    val file: ProjectFile,
    val modifiedAt: Long?,
    val source: IndexedGeneralSource? = null,
    val plot: IndexedPlot? = null,
) {
    val key: FileKey get() = file.key

    fun isFresh(projectFile: ProjectFile, observedModifiedAt: Long?): Boolean =
        file.platformFile.toString() == projectFile.platformFile.toString() &&
            (
                observedModifiedAt == null || observedModifiedAt <= 0L ||
                    modifiedAt == observedModifiedAt
            )
}

internal data class IndexedGeneralSource(
    val value: String?,
    val error: String? = null,
    val readSucceeded: Boolean = true,
)

internal data class IndexedPlot(
    val stage: PlotStage?,
    val readSucceeded: Boolean = true,
)
