package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface WorkspaceLinkIndexState {
    data object Inactive : WorkspaceLinkIndexState

    data class Building(
        val vaultIdentity: String,
        val processed: Int,
        val total: Int,
        val index: WorkspaceLinkIndex,
    ) : WorkspaceLinkIndexState

    data class Ready(
        val vaultIdentity: String,
        val index: WorkspaceLinkIndex,
        val issues: Map<WorkspaceLinkPath, String>,
        val cacheWarning: String? = null,
    ) : WorkspaceLinkIndexState

    data class Error(
        val vaultIdentity: String,
        val message: String,
        val previousIndex: WorkspaceLinkIndex? = null,
    ) : WorkspaceLinkIndexState
}

internal data class WorkspaceLinkMarkdownChange(
    val previousLocation: String,
    val previousPath: WorkspaceLinkPath,
    val platformFile: PlatformFile,
    val path: WorkspaceLinkPath,
    val rawMarkdown: String,
    val modifiedAt: Long?,
)

internal data class WorkspaceLinkPathChange(
    val previousLocation: String,
    val previousPath: WorkspaceLinkPath,
    val platformFile: PlatformFile,
    val path: WorkspaceLinkPath,
)

internal data class WorkspaceLinkIndexPreparation(
    val vaultIdentity: String,
    val generation: Long,
    val mutationRevision: Long,
    val indexRevision: Long,
    val index: WorkspaceLinkIndex,
)

/**
 * Owns the current Vault's derived link index. The Vault remains authoritative; persisted data is
 * only reused when a resource's path and last-modified value still match the strict inventory.
 */
class WorkspaceLinkIndexService(
    private val dataStore: DataStore<Preferences>,
    private val unicodeNormalizer: (String) -> String = ::normalizeUnicodeNfc,
) {
    private val mutex = Mutex()
    private val cacheMutex = Mutex()
    private val recordsByLocation = linkedMapOf<String, WorkspaceLinkRecord>()
    private val processRecords = mutableMapOf<String, Map<String, WorkspaceLinkRecord>>()
    private val processInvalidatedLocations = mutableMapOf<String, Set<String>>()
    private val platformFilesByPath = linkedMapOf<WorkspaceLinkPath, PlatformFile>()
    private val pendingUpserts = linkedMapOf<String, PendingWorkspaceLinkUpsert>()
    private val pendingRemovals = mutableSetOf<String>()
    private var generation = 0L
    private var mutationRevision = 0L
    private var indexRevision = 0L
    private var publishedIndexRevision = 0L
    private val _state = MutableStateFlow<WorkspaceLinkIndexState>(WorkspaceLinkIndexState.Inactive)
    val state: StateFlow<WorkspaceLinkIndexState> = _state.asStateFlow()

    internal suspend fun readyPreparation(vaultIdentity: String): WorkspaceLinkIndexPreparation? = mutex.withLock {
        val ready = _state.value as? WorkspaceLinkIndexState.Ready ?: return@withLock null
        if (ready.vaultIdentity != vaultIdentity || publishedIndexRevision != indexRevision) return@withLock null
        WorkspaceLinkIndexPreparation(vaultIdentity, generation, mutationRevision, indexRevision, ready.index)
    }

    internal suspend fun isCurrent(prepared: WorkspaceLinkIndexPreparation): Boolean = mutex.withLock {
        matchesPreparation(prepared)
    }

    private fun matchesPreparation(prepared: WorkspaceLinkIndexPreparation): Boolean =
        (_state.value as? WorkspaceLinkIndexState.Ready)?.vaultIdentity == prepared.vaultIdentity &&
            generation == prepared.generation && mutationRevision == prepared.mutationRevision &&
            indexRevision == prepared.indexRevision && publishedIndexRevision == indexRevision

    suspend fun rebuild(
        inventory: VaultLinkInventory,
        readMarkdown: suspend (PlatformFile) -> String,
        lastModified: suspend (PlatformFile) -> Long?,
        refreshMarkdown: Boolean = false,
    ): WorkspaceLinkIndex = rebuild(
        vaultIdentity = inventory.vaultLocation,
        inventoryProvider = { inventory },
        readMarkdown = readMarkdown,
        lastModified = lastModified,
        refreshMarkdown = refreshMarkdown,
    )

    suspend fun rebuild(
        vaultIdentity: String,
        inventoryProvider: suspend () -> VaultLinkInventory,
        readMarkdown: suspend (PlatformFile) -> String,
        lastModified: suspend (PlatformFile) -> Long?,
        refreshMarkdown: Boolean = false,
    ): WorkspaceLinkIndex {
        var warmRecords = emptyMap<String, WorkspaceLinkRecord>()
        var invalidatedLocations = emptySet<String>()
        var visibleIndex: WorkspaceLinkIndex? = null
        val rebuildGeneration = mutex.withLock {
            val current = _state.value
            rememberCurrentWorkspace()
            val previousIndex = when (current) {
                is WorkspaceLinkIndexState.Ready -> current.index.takeIf { current.vaultIdentity == vaultIdentity }
                is WorkspaceLinkIndexState.Building -> current.index.takeIf { current.vaultIdentity == vaultIdentity }
                is WorkspaceLinkIndexState.Error -> current.previousIndex.takeIf { current.vaultIdentity == vaultIdentity }
                WorkspaceLinkIndexState.Inactive -> null
            }
            warmRecords = if (current is WorkspaceLinkIndexState.Ready && current.vaultIdentity == vaultIdentity) {
                recordsByLocation.toMap()
            } else processRecords[vaultIdentity].orEmpty()
            invalidatedLocations = processInvalidatedLocations[vaultIdentity].orEmpty()
            visibleIndex = previousIndex
            val replacesSameVaultBuild = (_state.value as? WorkspaceLinkIndexState.Building)
                ?.vaultIdentity == vaultIdentity
            generation += 1
            if (!replacesSameVaultBuild) {
                pendingUpserts.clear()
                pendingRemovals.clear()
            }
            _state.value = WorkspaceLinkIndexState.Building(
                vaultIdentity = vaultIdentity,
                processed = 0,
                total = 0,
                index = previousIndex ?: buildIndex(emptyList()),
            )
            generation
        }
        try {
        val cachedByPath = buildMap {
            readCache(vaultIdentity)?.documents.orEmpty().forEach { put(it.document.cacheKey(), it) }
            warmRecords.values.forEach { record ->
                put(
                    record.document.path.cacheKey(),
                    CachedWorkspaceLinkRecord(
                        record.modifiedAt.takeIf { record.modifiedAtVerified },
                        CachedWorkspaceLinkDocument.from(record.document),
                    ),
                )
            }
        }

        while (true) {
            val inventoryRevision = mutex.withLock {
                ensureCurrent(rebuildGeneration)
                mutationRevision
            }
            val inventory = WorkspaceLoadDiagnostics.measure("link-inventory") { inventoryProvider() }
            require(inventory.vaultLocation == vaultIdentity) { "Vault inventory identity changed during rebuild." }
            val inventoryIsStable = mutex.withLock {
                ensureCurrent(rebuildGeneration)
                mutationRevision == inventoryRevision
            }
            if (!inventoryIsStable) continue

            val next = linkedMapOf<String, WorkspaceLinkRecord>()
            val nextFiles = linkedMapOf<WorkspaceLinkPath, PlatformFile>()
            val total = inventory.entries.size
            mutex.withLock {
                ensureCurrent(rebuildGeneration)
                val previousIndex = (_state.value as? WorkspaceLinkIndexState.Building)?.index
                _state.value = WorkspaceLinkIndexState.Building(
                    vaultIdentity = vaultIdentity,
                    processed = 0,
                    total = total,
                    index = previousIndex ?: buildIndex(emptyList()),
                )
            }

            inventory.entries.sortedBy(VaultLinkEntry::vaultRelativePath).forEachIndexed { index, entry ->
                val path = WorkspaceLinkPath(
                    workspaceKind = entry.workspace.kind,
                    workspaceName = entry.workspace.name,
                    relativePath = entry.workspaceRelativeKey.relativePath,
                )
                val modifiedAt = try {
                    lastModified(entry.platformFile)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                val processRecord = warmRecords[entry.platformFile.toString()]?.takeUnless { refreshMarkdown }?.takeIf {
                    it.document.path == path && it.document.issue == null &&
                        (modifiedAt == null || modifiedAt <= 0L || it.modifiedAt == null || it.modifiedAt <= 0L)
                }
                val processDocument = processRecord?.document
                val cachedDocument = processDocument ?: cachedByPath[path.cacheKey()]?.takeUnless {
                    refreshMarkdown || entry.platformFile.toString() in invalidatedLocations
                }
                    ?.takeIf { modifiedAt != null && modifiedAt > 0L && it.modifiedAt == modifiedAt }
                    ?.document
                    ?.toDomainOrNull()
                    ?.takeIf { it.issue == null }
                WorkspaceLoadDiagnostics.count(if (cachedDocument != null) WorkspaceLoadIo.CACHE_HIT else WorkspaceLoadIo.CACHE_MISS)
                val document = cachedDocument ?: when (entry.kind) {
                    VaultLinkEntryKind.ATTACHMENT -> WorkspaceLinkDocument.attachment(path)
                    VaultLinkEntryKind.MARKDOWN -> try {
                        WorkspaceLinkDocument.markdown(path, readMarkdown(entry.platformFile))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        WorkspaceLinkDocument.unreadableMarkdown(
                            path,
                            error.message ?: "문서를 읽지 못했습니다.",
                        )
                    }
                }
                val rememberedTime = if (processRecord != null) processRecord.modifiedAt else modifiedAt
                next[entry.platformFile.toString()] = WorkspaceLinkRecord(document, rememberedTime, processRecord?.modifiedAtVerified ?: true)
                nextFiles[path] = entry.platformFile

                val processed = index + 1
                if (total <= BUILD_PROGRESS_BATCH || processed == total || processed % BUILD_PROGRESS_BATCH == 0) {
                    val partial = visibleIndex ?: buildIndex(next.values.map(WorkspaceLinkRecord::document))
                    mutex.withLock {
                        ensureCurrent(rebuildGeneration)
                        _state.value = WorkspaceLinkIndexState.Building(
                            vaultIdentity = vaultIdentity,
                            processed = processed,
                            total = total,
                            index = partial,
                        )
                    }
                }
            }

            val committed = commitRebuild(rebuildGeneration, vaultIdentity, next, nextFiles) ?: continue
            updateCacheWarning(
                vaultIdentity = vaultIdentity,
                records = committed.records,
                expectedRevision = committed.revision,
            )
            return committed.index
        }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            reportFailure(vaultIdentity, error, rebuildGeneration)
            throw error
        }
    }

    suspend fun upsertByLocation(
        platformLocation: String,
        rawMarkdown: String,
        modifiedAt: Long?,
    ): Boolean = upsertByLocation(platformLocation, rawMarkdown, modifiedAt, null)

    internal suspend fun upsertByLocation(
        platformLocation: String,
        rawMarkdown: String,
        modifiedAt: Long?,
        expectedPreparation: WorkspaceLinkIndexPreparation?,
    ): Boolean {
        var shouldPublish = false
        val accepted = mutex.withLock {
            if (expectedPreparation != null && !matchesPreparation(expectedPreparation)) return@withLock false
            when (val current = _state.value) {
                is WorkspaceLinkIndexState.Building -> {
                    mutationRevision += 1
                    pendingRemovals.remove(platformLocation)
                    pendingUpserts[platformLocation] = PendingWorkspaceLinkUpsert(rawMarkdown, modifiedAt)
                    true
                }
                is WorkspaceLinkIndexState.Ready -> {
                    val previous = recordsByLocation[platformLocation] ?: return@withLock false
                    if (previous.document.resourceKind != WorkspaceLinkResourceKind.MARKDOWN) return@withLock false
                    val updated = WorkspaceLinkRecord(
                        document = WorkspaceLinkDocument.markdown(previous.document.path, rawMarkdown),
                        modifiedAt = modifiedAt?.takeIf { it > 0L } ?: previous.modifiedAt,
                        modifiedAtVerified = modifiedAt?.let { it > 0L } == true || previous.modifiedAt?.let { it > 0L } != true,
                    )
                    if (updated != previous) {
                        mutationRevision += 1
                        recordsByLocation[platformLocation] = updated
                        indexRevision += 1
                        shouldPublish = true
                    }
                    true
                }
                else -> false
            }
        }
        if (shouldPublish) {
            publishReadyInMemory()?.let { updateCacheWarning(it) }
        }
        return accepted
    }

    suspend fun removeByLocation(platformLocation: String): Boolean {
        var shouldPublish = false
        val accepted = mutex.withLock {
            when (val current = _state.value) {
                is WorkspaceLinkIndexState.Building -> {
                    mutationRevision += 1
                    pendingUpserts.remove(platformLocation)
                    pendingRemovals += platformLocation
                    true
                }
                is WorkspaceLinkIndexState.Ready -> {
                    val removed = recordsByLocation.remove(platformLocation) ?: return@withLock false
                    mutationRevision += 1
                    platformFilesByPath.remove(removed.document.path)
                    indexRevision += 1
                    shouldPublish = true
                    true
                }
                else -> false
            }
        }
        if (shouldPublish) {
            publishReadyInMemory()?.let { updateCacheWarning(it) }
        }
        return accepted
    }

    /** Applies a completed file transaction without another Vault inventory or content read. */
    internal suspend fun applyKnownMarkdownChanges(
        vaultIdentity: String,
        changes: List<WorkspaceLinkMarkdownChange>,
    ): Boolean = applyKnownMarkdownChangesAndGetIndex(vaultIdentity, changes) != null

    internal suspend fun applyKnownMarkdownChangesAndGetIndex(
        vaultIdentity: String,
        changes: List<WorkspaceLinkMarkdownChange>,
        expectedPreparation: WorkspaceLinkIndexPreparation? = null,
    ): WorkspaceLinkIndex? = applyKnownPathChangesAndGetIndex(vaultIdentity, emptyList(), changes, expectedPreparation)

    internal suspend fun applyKnownPathChangesAndGetIndex(
        vaultIdentity: String,
        pathChanges: List<WorkspaceLinkPathChange>,
        markdownChanges: List<WorkspaceLinkMarkdownChange> = emptyList(),
        expectedPreparation: WorkspaceLinkIndexPreparation? = null,
    ): WorkspaceLinkIndex? {
        val rawByLocation = markdownChanges.associateBy { it.previousLocation }
        if (rawByLocation.size != markdownChanges.size) return null
        val pathsByLocation = pathChanges.associateBy { it.previousLocation }
        if (pathsByLocation.size != pathChanges.size) return null
        if (markdownChanges.any { raw -> pathsByLocation[raw.previousLocation]?.let {
                it.previousPath != raw.previousPath || it.path != raw.path ||
                    it.platformFile.toString() != raw.platformFile.toString()
            } == true }) return null
        val changes = pathChanges + markdownChanges.filter { it.previousLocation !in pathsByLocation }
            .map { WorkspaceLinkPathChange(it.previousLocation, it.previousPath, it.platformFile, it.path) }
        val applied = mutex.withLock {
            val ready = _state.value as? WorkspaceLinkIndexState.Ready ?: return null
            if (ready.vaultIdentity != vaultIdentity || publishedIndexRevision != indexRevision ||
                (expectedPreparation != null && !matchesPreparation(expectedPreparation))) return null
            if (changes.isEmpty()) return ready.index
            val oldLocations = changes.map { it.previousLocation }.toSet()
            val oldPaths = changes.map { it.previousPath }.toSet()
            val newLocations = changes.map { it.platformFile.toString() }.toSet()
            val newPaths = changes.map { it.path }.toSet()
            if (listOf(oldLocations.size, oldPaths.size, newLocations.size, newPaths.size)
                    .any { it != changes.size }) return null
            if (changes.any { change ->
                    val previous = recordsByLocation[change.previousLocation]
                    previous == null || previous.document.path != change.previousPath ||
                        (change.previousLocation in rawByLocation &&
                            previous.document.resourceKind != WorkspaceLinkResourceKind.MARKDOWN) ||
                        platformFilesByPath[change.previousPath]?.toString() != change.previousLocation
                }) return null
            if (newLocations.any { it in recordsByLocation && it !in oldLocations } ||
                newPaths.any { it in platformFilesByPath && it !in oldPaths }) return null

            val next = LinkedHashMap(recordsByLocation)
            oldLocations.forEach(next::remove)
            changes.forEach { change ->
                val previous = recordsByLocation.getValue(change.previousLocation)
                val raw = rawByLocation[change.previousLocation]
                next[change.platformFile.toString()] = WorkspaceLinkRecord(
                    raw?.let { WorkspaceLinkDocument.markdown(change.path, it.rawMarkdown) }
                        ?: previous.document.copy(path = change.path),
                    if (raw != null) raw.modifiedAt?.takeIf { it > 0L } ?: previous.modifiedAt else previous.modifiedAt,
                    if (raw != null) raw.modifiedAt?.let { it > 0L } == true || previous.modifiedAt?.let { it > 0L } != true
                        else previous.modifiedAtVerified,
                )
            }
            val index = buildIndex(next.values.map(WorkspaceLinkRecord::document))
            recordsByLocation.clear()
            recordsByLocation.putAll(next)
            oldPaths.forEach(platformFilesByPath::remove)
            changes.forEach { platformFilesByPath[it.path] = it.platformFile }
            mutationRevision += 1
            indexRevision += 1
            publishedIndexRevision = indexRevision
            _state.value = WorkspaceLinkIndexState.Ready(
                vaultIdentity = vaultIdentity,
                index = index,
                issues = index.documents.mapNotNull { document -> document.issue?.let { document.path to it } }.toMap(),
            )
            index to currentCacheSnapshot(vaultIdentity)
        }
        updateCacheWarning(applied.second)
        return applied.first
    }

    internal suspend fun insertKnownMarkdown(
        vaultIdentity: String,
        file: PlatformFile,
        path: WorkspaceLinkPath,
        rawMarkdown: String,
        modifiedAt: Long?,
    ): Boolean {
        var publish = false
        val accepted = mutex.withLock {
            if (_state.value.vaultIdentityOrNull() != vaultIdentity) return@withLock false
            when (_state.value) {
                is WorkspaceLinkIndexState.Building -> {
                    pendingRemovals.remove(file.toString())
                    pendingUpserts[file.toString()] = PendingWorkspaceLinkUpsert(rawMarkdown, modifiedAt)
                    mutationRevision += 1
                    true
                }
                is WorkspaceLinkIndexState.Ready -> {
                    val previous = recordsByLocation[file.toString()]
                    if (previous != null && previous.document.path != path ||
                        platformFilesByPath[path]?.toString()?.let { it != file.toString() } == true) return@withLock false
                    recordsByLocation[file.toString()] = WorkspaceLinkRecord(
                        WorkspaceLinkDocument.markdown(path, rawMarkdown), modifiedAt,
                    )
                    platformFilesByPath[path] = file
                    mutationRevision += 1
                    indexRevision += 1
                    publish = true
                    true
                }
                else -> false
            }
        }
        if (publish) publishReadyInMemory()?.let { updateCacheWarning(it) }
        return accepted
    }

    suspend fun reportFailure(vaultIdentity: String, error: Throwable, expectedGeneration: Long? = null) = mutex.withLock {
        if (_state.value.vaultIdentityOrNull() != vaultIdentity) return@withLock
        if (expectedGeneration != null && generation != expectedGeneration) return@withLock
        generation += 1
        val previous = when (val current = _state.value) {
            is WorkspaceLinkIndexState.Ready -> current.index
            is WorkspaceLinkIndexState.Building -> current.index
            is WorkspaceLinkIndexState.Error -> current.previousIndex
            WorkspaceLinkIndexState.Inactive -> null
        }
        _state.value = WorkspaceLinkIndexState.Error(
            vaultIdentity = vaultIdentity,
            message = error.message ?: "링크 인덱스를 구성하지 못했습니다.",
            previousIndex = previous,
        )
    }

    suspend fun deactivate() = mutex.withLock {
        rememberCurrentWorkspace()
        generation += 1
        recordsByLocation.clear()
        platformFilesByPath.clear()
        pendingUpserts.clear()
        pendingRemovals.clear()
        _state.value = WorkspaceLinkIndexState.Inactive
    }

    /** Preserve accepted saves before a build or its Vault is replaced. Called under mutex. */
    private fun rememberCurrentWorkspace() {
        val current = _state.value
        val vaultIdentity = current.vaultIdentityOrNull() ?: return
        val remembered = LinkedHashMap(
            if (current is WorkspaceLinkIndexState.Ready) recordsByLocation
            else processRecords[vaultIdentity].orEmpty(),
        )
        val invalidated = processInvalidatedLocations[vaultIdentity].orEmpty().toMutableSet()
        invalidated += pendingRemovals
        pendingRemovals.forEach(remembered::remove)
        pendingUpserts.forEach { (location, pending) ->
            val previous = remembered[location]
            if (previous == null) {
                // Its path is not known yet. A matching persisted mtime must not revive old content.
                invalidated += location
                return@forEach
            }
            if (previous.document.resourceKind == WorkspaceLinkResourceKind.MARKDOWN) {
                remembered[location] = WorkspaceLinkRecord(
                    WorkspaceLinkDocument.markdown(previous.document.path, pending.rawMarkdown),
                    pending.modifiedAt?.takeIf { it > 0L } ?: previous.modifiedAt,
                    pending.modifiedAt?.let { it > 0L } == true || previous.modifiedAt?.let { it > 0L } != true,
                )
                invalidated -= location
            }
        }
        processRecords[vaultIdentity] = remembered
        processInvalidatedLocations[vaultIdentity] = invalidated
    }

    suspend fun platformFile(path: WorkspaceLinkPath): PlatformFile? = mutex.withLock {
        platformFilesByPath[path]
    }

    private suspend fun publishReadyInMemory(): WorkspaceLinkCacheSnapshot? {
        while (true) {
            val snapshot = mutex.withLock {
                val ready = _state.value as? WorkspaceLinkIndexState.Ready ?: return null
                currentCacheSnapshot(ready.vaultIdentity)
            }
            val index = buildIndex(snapshot.records.map(WorkspaceLinkRecord::document))
            val published = mutex.withLock {
                val ready = _state.value as? WorkspaceLinkIndexState.Ready
                if (ready?.vaultIdentity != snapshot.vaultIdentity || indexRevision != snapshot.revision) {
                    false
                } else {
                    _state.value = WorkspaceLinkIndexState.Ready(
                        vaultIdentity = snapshot.vaultIdentity,
                        index = index,
                        issues = index.documents.mapNotNull { document ->
                            document.issue?.let { document.path to it }
                        }.toMap(),
                        cacheWarning = null,
                    )
                    publishedIndexRevision = indexRevision
                    true
                }
            }
            if (published) return snapshot
        }
    }

    private suspend fun commitRebuild(
        rebuildGeneration: Long,
        vaultIdentity: String,
        next: LinkedHashMap<String, WorkspaceLinkRecord>,
        nextFiles: LinkedHashMap<WorkspaceLinkPath, PlatformFile>,
    ): CommittedWorkspaceLinkIndex? {
        while (true) {
            val mutations = mutex.withLock {
                ensureCurrent(rebuildGeneration)
                PendingWorkspaceLinkMutations(pendingUpserts.toMap(), pendingRemovals.toSet())
            }
            mutations.removals.forEach { location ->
                next.remove(location)?.document?.path?.let(nextFiles::remove)
            }
            var requiresInventoryRefresh = false
            mutations.upserts.forEach { (location, pending) ->
                val previous = next[location]
                if (previous == null) {
                    requiresInventoryRefresh = true
                    return@forEach
                }
                if (previous.document.resourceKind == WorkspaceLinkResourceKind.MARKDOWN) {
                    next[location] = WorkspaceLinkRecord(
                        WorkspaceLinkDocument.markdown(previous.document.path, pending.rawMarkdown),
                        pending.modifiedAt?.takeIf { it > 0L } ?: previous.modifiedAt,
                    pending.modifiedAt?.let { it > 0L } == true || previous.modifiedAt?.let { it > 0L } != true,
                    )
                }
            }
            if (requiresInventoryRefresh) return null
            val built = buildIndex(next.values.map(WorkspaceLinkRecord::document))
            val committedRevision = mutex.withLock {
                ensureCurrent(rebuildGeneration)
                if (pendingUpserts != mutations.upserts || pendingRemovals != mutations.removals) {
                    null
                } else {
                    pendingUpserts.clear()
                    pendingRemovals.clear()
                    recordsByLocation.clear()
                    recordsByLocation.putAll(next)
                    processRecords[vaultIdentity] = next.toMap()
                    processInvalidatedLocations.remove(vaultIdentity)
                    platformFilesByPath.clear()
                    platformFilesByPath.putAll(nextFiles)
                    indexRevision += 1
                    publishedIndexRevision = indexRevision
                    _state.value = WorkspaceLinkIndexState.Ready(
                        vaultIdentity = vaultIdentity,
                        index = built,
                        issues = built.documents.mapNotNull { document ->
                            document.issue?.let { document.path to it }
                        }.toMap(),
                    )
                    indexRevision
                }
            }
            if (committedRevision != null) {
                return CommittedWorkspaceLinkIndex(
                    index = built,
                    records = next.values.toList(),
                    revision = committedRevision,
                )
            }
        }
    }

    private fun ensureCurrent(expectedGeneration: Long) {
        if (generation != expectedGeneration) throw CancellationException("링크 인덱스 재구성이 교체되었습니다.")
    }

    private fun currentCacheSnapshot(vaultIdentity: String): WorkspaceLinkCacheSnapshot {
        processRecords[vaultIdentity] = recordsByLocation.toMap()
        return WorkspaceLinkCacheSnapshot(
        vaultIdentity = vaultIdentity,
        records = recordsByLocation.values.toList(),
        revision = indexRevision,
        )
    }

    private suspend fun updateCacheWarning(snapshot: WorkspaceLinkCacheSnapshot) = updateCacheWarning(
        vaultIdentity = snapshot.vaultIdentity,
        records = snapshot.records,
        expectedRevision = snapshot.revision,
    )

    private suspend fun updateCacheWarning(
        vaultIdentity: String,
        records: List<WorkspaceLinkRecord>,
        expectedRevision: Long,
    ) {
        val warning = cacheMutex.withLock {
            val isCurrent = mutex.withLock {
                val ready = _state.value as? WorkspaceLinkIndexState.Ready
                ready?.vaultIdentity == vaultIdentity && indexRevision == expectedRevision
            }
            if (!isCurrent) return@withLock null
            cacheWriteWarning(vaultIdentity, records)
        }
        mutex.withLock {
            val ready = _state.value as? WorkspaceLinkIndexState.Ready
            if (ready?.vaultIdentity == vaultIdentity && indexRevision == expectedRevision) {
                _state.value = ready.copy(cacheWarning = warning)
            }
        }
    }

    private suspend fun buildIndex(documents: Iterable<WorkspaceLinkDocument>): WorkspaceLinkIndex =
        WorkspaceLoadDiagnostics.measure("link-index-build") {
            WorkspaceLinkIndex(unicodeNormalizer).also { it.upsert(documents) }
        }

    private suspend fun readCache(vaultIdentity: String): CachedWorkspaceLinkIndex? {
        val raw = dataStore.data.first()[cacheKey(vaultIdentity)] ?: return null
        return runCatching { cacheJson.decodeFromString<CachedWorkspaceLinkIndex>(raw) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == CACHE_SCHEMA_VERSION && it.vaultIdentity == vaultIdentity }
    }

    private suspend fun writeCache(vaultIdentity: String, records: Collection<WorkspaceLinkRecord>) =
        WorkspaceLoadDiagnostics.measure("link-cache-store") {
            val cached = CachedWorkspaceLinkIndex(
                vaultIdentity = vaultIdentity,
                documents = records.map { record ->
                    CachedWorkspaceLinkRecord(record.modifiedAt.takeIf { record.modifiedAtVerified }, CachedWorkspaceLinkDocument.from(record.document))
                },
            )
            dataStore.edit { preferences ->
                preferences[cacheKey(vaultIdentity)] = WorkspaceLoadDiagnostics.measure("link-cache-encode") {
                    cacheJson.encodeToString(cached)
                }
            }
        }

    private suspend fun cacheWriteWarning(
        vaultIdentity: String,
        records: Collection<WorkspaceLinkRecord>,
    ): String? = try {
        writeCache(vaultIdentity, records)
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        error.message
    }

    private fun cacheKey(vaultIdentity: String) = stringPreferencesKey(
        "workspace_link_index_v${CACHE_SCHEMA_VERSION}_${vaultIdentity.hashCode().toUInt().toString(16)}",
    )

    private companion object {
        const val CACHE_SCHEMA_VERSION = 4
        const val BUILD_PROGRESS_BATCH = 32
        val cacheJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

private fun WorkspaceLinkIndexState.vaultIdentityOrNull(): String? = when (this) {
    is WorkspaceLinkIndexState.Building -> vaultIdentity
    is WorkspaceLinkIndexState.Ready -> vaultIdentity
    is WorkspaceLinkIndexState.Error -> vaultIdentity
    WorkspaceLinkIndexState.Inactive -> null
}

private data class PendingWorkspaceLinkUpsert(
    val rawMarkdown: String,
    val modifiedAt: Long?,
)

private data class PendingWorkspaceLinkMutations(
    val upserts: Map<String, PendingWorkspaceLinkUpsert>,
    val removals: Set<String>,
)

private data class CommittedWorkspaceLinkIndex(
    val index: WorkspaceLinkIndex,
    val records: List<WorkspaceLinkRecord>,
    val revision: Long,
)

private data class WorkspaceLinkCacheSnapshot(
    val vaultIdentity: String,
    val records: List<WorkspaceLinkRecord>,
    val revision: Long,
)

private data class WorkspaceLinkRecord(
    val document: WorkspaceLinkDocument,
    val modifiedAt: Long?,
    val modifiedAtVerified: Boolean = true,
)

@Serializable
private data class CachedWorkspaceLinkIndex(
    val schemaVersion: Int = 4,
    val vaultIdentity: String,
    val documents: List<CachedWorkspaceLinkRecord>,
)

@Serializable
private data class CachedWorkspaceLinkRecord(
    val modifiedAt: Long?,
    val document: CachedWorkspaceLinkDocument,
)

@Serializable
private data class CachedWorkspaceLinkDocument(
    val workspaceKind: String,
    val workspaceName: String,
    val relativePath: String,
    val resourceKind: String,
    val aliases: List<String>,
    val headings: List<CachedWorkspaceLinkHeading>,
    val blocks: List<CachedWorkspaceLinkBlock>,
    val blockDrafts: List<CachedWorkspaceLinkBlockDraft>,
    val outgoing: List<CachedWorkspaceLinkReference>,
    val issue: String?,
) {
    fun toDomainOrNull(): WorkspaceLinkDocument? = runCatching {
        WorkspaceLinkDocument(
            path = WorkspaceLinkPath(WorkspaceKind.valueOf(workspaceKind), workspaceName, relativePath),
            resourceKind = WorkspaceLinkResourceKind.valueOf(resourceKind),
            aliases = aliases,
            headings = headings.map { it.toDomain() },
            blocks = blocks.map { it.toDomain() },
            blockDrafts = blockDrafts.map { it.toDomain() },
            outgoing = outgoing.map { it.toDomain() },
            issue = issue,
        )
    }.getOrNull()

    companion object {
        fun from(document: WorkspaceLinkDocument) = CachedWorkspaceLinkDocument(
            workspaceKind = document.path.workspaceKind.name,
            workspaceName = document.path.workspaceName,
            relativePath = document.path.relativePath,
            resourceKind = document.resourceKind.name,
            aliases = document.aliases,
            headings = document.headings.map { CachedWorkspaceLinkHeading.from(it) },
            blocks = document.blocks.map { CachedWorkspaceLinkBlock.from(it) },
            blockDrafts = document.blockDrafts.map { CachedWorkspaceLinkBlockDraft.from(it) },
            outgoing = document.outgoing.map { CachedWorkspaceLinkReference.from(it) },
            issue = document.issue,
        )
    }
}

@Serializable
private data class CachedWorkspaceLinkHeading(
    val level: Int,
    val text: String,
    val path: String,
    val start: Int,
    val end: Int,
) {
    fun toDomain() = WorkspaceLinkHeading(level, text, WorkspaceLinkSourceRange(start, end), path)
    companion object {
        fun from(value: WorkspaceLinkHeading) = CachedWorkspaceLinkHeading(
            value.level, value.text, value.path, value.sourceRange.start, value.sourceRange.endExclusive,
        )
    }
}

@Serializable
private data class CachedWorkspaceLinkBlock(val id: String, val start: Int, val end: Int, val preview: String) {
    fun toDomain() = WorkspaceLinkBlock(id, WorkspaceLinkSourceRange(start, end), preview)
    companion object {
        fun from(value: WorkspaceLinkBlock) = CachedWorkspaceLinkBlock(
            value.id, value.sourceRange.start, value.sourceRange.endExclusive, value.preview,
        )
    }
}

@Serializable
private data class CachedWorkspaceLinkBlockDraft(
    val start: Int,
    val end: Int,
    val expectedText: String,
    val insertionOffset: Int,
    val insertionPrefix: String,
    val insertionSuffix: String,
) {
    fun toDomain(): WorkspaceLinkBlockDraft {
        require(insertionOffset in start..end && expectedText.length == end - start)
        return WorkspaceLinkBlockDraft(WorkspaceLinkSourceRange(start, end), expectedText, insertionOffset, insertionPrefix, insertionSuffix)
    }
    companion object {
        fun from(value: WorkspaceLinkBlockDraft) = CachedWorkspaceLinkBlockDraft(
            value.sourceRange.start, value.sourceRange.endExclusive, value.expectedText,
            value.insertionOffset, value.insertionPrefix, value.insertionSuffix,
        )
    }
}

@Serializable
private data class CachedWorkspaceLinkReference(
    val syntaxKind: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val targetStart: Int,
    val targetEnd: Int,
    val rawTarget: String,
    val file: String?,
    val heading: String?,
    val block: String?,
    val display: String?,
    val malformedPercentEncoding: Boolean,
) {
    fun toDomain() = WorkspaceLinkReference(
        syntaxKind = WorkspaceLinkSyntaxKind.valueOf(syntaxKind),
        sourceRange = WorkspaceLinkSourceRange(sourceStart, sourceEnd),
        targetRange = WorkspaceLinkSourceRange(targetStart, targetEnd),
        rawTarget = rawTarget,
        target = WorkspaceLinkTarget(file, heading, block, display, malformedPercentEncoding),
    )

    companion object {
        fun from(value: WorkspaceLinkReference) = CachedWorkspaceLinkReference(
            syntaxKind = value.syntaxKind.name,
            sourceStart = value.sourceRange.start,
            sourceEnd = value.sourceRange.endExclusive,
            targetStart = value.targetRange.start,
            targetEnd = value.targetRange.endExclusive,
            rawTarget = value.rawTarget,
            file = value.target.file,
            heading = value.target.heading,
            block = value.target.block,
            display = value.target.display,
            malformedPercentEncoding = value.target.malformedPercentEncoding,
        )
    }
}

private fun WorkspaceLinkPath.cacheKey(): String =
    "${workspaceKind.name}\u0000$workspaceName\u0000$relativePath"

private fun CachedWorkspaceLinkDocument.cacheKey(): String =
    "$workspaceKind\u0000$workspaceName\u0000$relativePath"
