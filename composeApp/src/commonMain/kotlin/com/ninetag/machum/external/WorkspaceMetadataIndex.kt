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
    private val processEntries = mutableMapOf<Pair<String, WorkspaceKind>, Map<FileKey, WorkspaceFileMetadata>>()

    suspend fun activate(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
    ): WorkspaceMetadataSnapshot = mutex.withLock {
        val location = workspace.toString()
        if (workspaceIdentity?.matches(location, workspaceKind) != true) {
            rememberCurrentWorkspace()
            activationRevision += 1
            workspaceIdentity = WorkspaceMetadataIdentity(location, workspaceKind, activationRevision)
            entries = processEntries[location to workspaceKind].orEmpty()
        }
        WorkspaceMetadataSnapshot(checkNotNull(workspaceIdentity), entries)
    }

    suspend fun deactivate() = mutex.withLock {
        rememberCurrentWorkspace()
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
        expectedIdentity: WorkspaceMetadataIdentity? = null,
    ) = mutex.withLock {
        if (workspaceIdentity?.matches(workspace.toString(), workspaceKind) != true) return@withLock
        if (expectedIdentity != null && workspaceIdentity != expectedIdentity) return@withLock
        val previous = entries[updated.key]
        val accepted = if ((updated.modifiedAt == null || updated.modifiedAt <= 0L) &&
            previous?.modifiedAt?.let { it > 0L } == true) updated.copy(modifiedAt = previous.modifiedAt, modifiedAtVerified = false)
            else updated
        entries = entries + (accepted.key to accepted)
    }

    suspend fun invalidate(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        keys: Collection<FileKey>,
    ) = mutex.withLock {
        if (workspaceIdentity?.matches(workspace.toString(), workspaceKind) != true) return@withLock
        if (keys.isNotEmpty()) {
            entries = entries - keys.toSet()
            activationRevision += 1
            workspaceIdentity = workspaceIdentity?.copy(activationRevision = activationRevision)
        }
    }

    /** Internal deletion cannot leave an unknown-time archive eligible for a recreated path. */
    suspend fun removeWorkspace(workspace: PlatformFile) = mutex.withLock {
        val location = workspace.toString()
        processEntries.keys.filter { it.first == location }.forEach(processEntries::remove)
        if (workspaceIdentity?.workspaceLocation == location) {
            entries = emptyMap()
            activationRevision += 1
            workspaceIdentity = null
        }
    }

    /** Verified transaction bodies also replace remembered inactive-workspace documents. */
    suspend fun acceptKnownMarkdownChanges(changes: List<WorkspaceLinkMarkdownChange>) = mutex.withLock {
        if (changes.isEmpty()) return@withLock
        rememberCurrentWorkspace()
        for ((identity, remembered) in processEntries.toMap()) {
            val replacements = changes.mapNotNull { change ->
                if (identity.second != change.previousPath.workspaceKind) return@mapNotNull null
                val previous = remembered.values.singleOrNull { it.file.platformFile.toString() == change.previousLocation }
                    ?: return@mapNotNull null
                if (previous.key.relativePath != change.previousPath.relativePath) return@mapNotNull null
                val note = NoteFile.parse(change.rawMarkdown)
                val file = ProjectFile(FileKey.of(change.path.relativePath), change.platformFile)
                val source = GeneralSourceProperty.read(change.rawMarkdown)
                previous.key to previous.copy(
                    file = file, noteFile = note,
                    projectMetadataVerified = false,
                    modifiedAt = change.modifiedAt?.takeIf { it > 0L } ?: previous.modifiedAt,
                    modifiedAtVerified = change.modifiedAt?.let { it > 0L } == true || previous.modifiedAt?.let { it > 0L } != true,
                    source = previous.source?.let { IndexedGeneralSource(source.value, source.error) },
                    plot = previous.plot?.let { IndexedPlot(note.plotStage) },
                )
            }
            val updated = (remembered - replacements.map { it.first }.toSet()) + replacements.associate { it.second.key to it.second }
            processEntries[identity] = updated
            if (workspaceIdentity?.matches(identity.first, identity.second) == true) entries = updated
        }
        // Reads started before this publication may also refer to a previously uncached file.
        activationRevision += 1
        workspaceIdentity = workspaceIdentity?.copy(activationRevision = activationRevision)
    }

    /** Protected rollback may restore both active and archived referrers after known-body publication. */
    suspend fun invalidateForRecovery() = mutex.withLock {
        entries = emptyMap()
        processEntries.clear()
        activationRevision += 1
        workspaceIdentity = workspaceIdentity?.copy(activationRevision = activationRevision)
    }

    suspend fun relocate(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        files: Map<FileKey, ProjectFile>,
        notes: Map<FileKey, NoteFile>,
    ) = mutex.withLock {
        if (workspaceIdentity?.matches(workspace.toString(), workspaceKind) != true) return@withLock
        val moved = relocatedEntries(entries, files, notes)
        entries = (entries - files.keys) + moved
    }

    suspend fun relocateWorkspace(
        previous: PlatformFile,
        updated: PlatformFile,
        workspaceKind: WorkspaceKind,
        files: Map<FileKey, ProjectFile>,
        notes: Map<FileKey, NoteFile>,
    ) = mutex.withLock {
        rememberCurrentWorkspace()
        val old = processEntries.remove(previous.toString() to workspaceKind).orEmpty()
        val moved = relocatedEntries(old, files, notes)
        processEntries[updated.toString() to workspaceKind] = moved
        if (workspaceIdentity?.matches(updated.toString(), workspaceKind) == true) entries = moved
    }

    private fun relocatedEntries(
        previous: Map<FileKey, WorkspaceFileMetadata>,
        files: Map<FileKey, ProjectFile>,
        notes: Map<FileKey, NoteFile>,
    ): Map<FileKey, WorkspaceFileMetadata> = files.mapNotNull { (oldKey, file) ->
        previous[oldKey]?.let { old ->
            val note = notes[file.key] ?: old.noteFile
            val source = note?.let { GeneralSourceProperty.read(it.inject()) }
            file.key to old.copy(file = file, noteFile = note,
                source = if (source != null) IndexedGeneralSource(source.value, source.error) else old.source,
                plot = if (note != null) IndexedPlot(note.plotStage) else old.plot)
        }
    }.toMap()

    private fun matches(
        workspace: PlatformFile,
        workspaceKind: WorkspaceKind,
        baseline: WorkspaceMetadataSnapshot,
    ): Boolean {
        val identity = workspaceIdentity ?: return false
        return identity.matches(workspace.toString(), workspaceKind) &&
            baseline.workspaceIdentity == identity
    }

    private fun rememberCurrentWorkspace() {
        workspaceIdentity?.let { processEntries[it.workspaceLocation to it.workspaceKind] = entries }
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
    val noteFile: NoteFile? = null,
    /** Set only after ProjectIndexer has checked the required id and Project tag. */
    val projectMetadataVerified: Boolean = false,
    /** A retained positive time must not certify a newly written body when the provider returned no time. */
    val modifiedAtVerified: Boolean = true,
) {
    val key: FileKey get() = file.key

    fun isFresh(projectFile: ProjectFile, observedModifiedAt: Long?): Boolean =
        file.platformFile.toString() == projectFile.platformFile.toString() &&
            (
                modifiedAt == null || modifiedAt <= 0L ||
                    observedModifiedAt == null || observedModifiedAt <= 0L ||
                    (modifiedAtVerified && modifiedAt == observedModifiedAt)
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
