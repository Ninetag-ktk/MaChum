package com.ninetag.machum.external

import com.ninetag.machum.entity.DocumentPropertyType
import com.ninetag.machum.entity.DocumentPropertyDefaults
import com.ninetag.machum.entity.DocumentPropertyDefinitionChange
import com.ninetag.machum.entity.documentPropertyKeyError
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

private const val GENERAL_SOURCE_CONFIG = ".machum-general.json"
private const val GENERAL_SOURCE_RECOVERY_PREFIX = ".machum-general-recovery-"
private const val GENERAL_SOURCE_SCAN_PARALLELISM = 4
private val GENERAL_MANAGED_PROPERTY_KEYS = setOf("id", "plot", "source", "tags")

@Serializable
private data class GeneralSourceRecovery(val target: String, val original: String?)

@Serializable
internal data class GeneralSourceConfig(
    val version: Int = 1,
    val groups: List<String> = emptyList(),
    val propertyTypes: Map<String, DocumentPropertyType> = emptyMap(),
    val defaultPropertyKeys: List<String> = emptyList(),
)

data class GeneralSourceEntry(val file: ProjectFile, val value: String?, val error: String? = null)
data class GeneralSourceState(
    val groups: List<String>,
    val files: List<GeneralSourceEntry>,
    val enabled: Boolean,
    val propertyTypes: Map<String, DocumentPropertyType> = emptyMap(),
    val defaultPropertyKeys: List<String> = emptyList(),
    val configuredGroups: List<String> = if (enabled) groups else emptyList(),
)
enum class GeneralSourceOperation { ASSIGN, RENAME, DELETE }

class GeneralSourcePlan internal constructor(
    internal val workspace: PlatformFile,
    val kind: GeneralSourceOperation,
    val oldName: String?,
    val targetName: String,
    val mergeRequired: Boolean,
    internal val changes: List<GeneralSourceChange>,
    internal val originalConfig: String?,
    internal val finalConfig: GeneralSourceConfig,
) {
    val files: List<ProjectFile> get() = changes.map { it.file }
    internal var configFailureSnapshot: String? = null
    internal var configWriteAttempted = false
    internal val failedWriteSnapshots = mutableMapOf<FileKey, String>()
    internal val completedFiles = mutableSetOf<FileKey>()
}

internal data class GeneralSourceChange(val file: ProjectFile, val original: String, val updated: String)
data class GeneralSourceResult(
    val complete: Boolean,
    val changedFiles: List<ProjectFile>,
    val failures: Map<FileKey, String>,
    val state: GeneralSourceState?,
    val plan: GeneralSourcePlan,
    val error: String? = null,
)

/** General-only grouping. Reading never writes configuration or Markdown. */
class GeneralSourceService internal constructor(
    private val manager: FileManager,
    private val metadataIndex: WorkspaceMetadataIndex = manager.workspaceMetadataIndex,
    private val readSource: suspend (PlatformFile) -> String = { it.readString() },
    private val writeRecovery: suspend (PlatformFile, String) -> Unit = manager::write,
    private val write: suspend (PlatformFile, String) -> Unit = manager::write,
) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(workspace: PlatformFile): GeneralSourceState = load(workspace, files = null)

    /** Reuses a complete hierarchy snapshot to avoid a second SAF directory enumeration. */
    internal suspend fun load(
        workspace: PlatformFile,
        files: List<ProjectFile>?,
    ): GeneralSourceState = mutex.withLock {
        withContext(Dispatchers.IO) { requireGeneral(workspace); loadUnlocked(workspace, files) }
    }

    /** Updates only General's workspace-wide property definitions under the source-config write fence. */
    suspend fun updateDocumentPropertyDefinition(
        workspace: PlatformFile,
        change: DocumentPropertyDefinitionChange,
    ): GeneralSourceState = updateDocumentPropertyDefinitions(workspace, listOf(change))

    suspend fun updateDocumentPropertyDefinitions(
        workspace: PlatformFile,
        changes: List<DocumentPropertyDefinitionChange>,
    ): GeneralSourceState = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireGeneral(workspace)
            val (raw, config) = readConfig(workspace)
            val userChanges = changes.filterNot { change ->
                val normalized = change.normalized()
                normalized.previousKey in GENERAL_MANAGED_PROPERTY_KEYS ||
                    normalized.key in GENERAL_MANAGED_PROPERTY_KEYS
            }
            val defaults = userChanges.fold(DocumentPropertyDefaults(config.propertyTypes, config.defaultPropertyKeys)) { current, item ->
                current.apply(item)
            }
            val updated = config.copy(
                propertyTypes = defaults.propertyTypes,
                defaultPropertyKeys = defaults.defaultPropertyKeys,
            )
            if (updated != config) saveConfig(workspace, raw, updated)
            loadUnlocked(workspace)
        }
    }

    suspend fun createGroup(workspace: PlatformFile): GeneralSourceState = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireGeneral(workspace)
            val (raw, config) = readConfig(workspace)
            val entries = scan(workspace)
            val allNames = (config.groups + entries.mapNotNull { it.value?.takeIf(String::isNotEmpty) }).distinct()
            var name = "무제"
            var suffix = 1
            while (allNames.any { it.equals(name, ignoreCase = true) }) name = "무제_${suffix++}"
            val updated = config.copy(groups = (config.groups + name).distinct().sorted())
            saveConfig(workspace, raw, updated)
            loadUnlocked(workspace)
        }
    }

    /** Used only during an explicitly requested new General file creation. */
    internal suspend fun initialContent(workspace: PlatformFile, raw: String, initialSource: String? = null): String = mutex.withLock {
        initialContentUnlocked(workspace, raw, initialSource)
    }

    /** Keep group validation and file allocation in the same grouping mutation boundary. */
    internal suspend fun createFile(
        workspace: PlatformFile,
        parent: PlatformFile,
        name: String,
        raw: String,
        initialSource: String?,
    ): PlatformFile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val content = initialContentUnlocked(workspace, raw, initialSource)
            manager.createFile(parent, name, content)
        }
    }

    private suspend fun initialContentUnlocked(workspace: PlatformFile, raw: String, initialSource: String?): String {
        requireGeneral(workspace)
        val config = readConfig(workspace).second
        val withDefaults = injectDefaultDocumentProperties(
            raw,
            config.defaultPropertyKeys,
            managedKeys = setOf("source", "tags"),
        )
        if (initialSource != null) {
            val indexedSources = metadataIndex.snapshot(workspace, WorkspaceKind.GENERAL)
                ?.entries
                ?.values
                .orEmpty()
                .mapNotNull { metadata ->
                    metadata.source
                        ?.takeIf(IndexedGeneralSource::readSucceeded)
                        ?.value
                        ?.takeIf(String::isNotEmpty)
                }
                .toSet()
            check(
                config.groups.isNotEmpty() &&
                    initialSource.isNotEmpty() &&
                    (initialSource in config.groups || initialSource in indexedSources),
            ) {
                "구분이 변경되었습니다. 목록을 새로고침한 뒤 다시 만들어 주세요."
            }
            return GeneralSourceProperty.write(withDefaults, initialSource)
        }
        return if (config.groups.isEmpty()) withDefaults else GeneralSourceProperty.write(withDefaults, "")
    }

    suspend fun planRename(workspace: PlatformFile, oldName: String, newName: String): GeneralSourcePlan = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireGeneral(workspace)
            val (raw, config) = readConfig(workspace)
            val state = loadUnlocked(workspace)
            check(state.enabled && oldName in state.groups) { "구분이 변경되었습니다. 목록을 새로고침해 주세요." }
            val requested = validName(newName)
            val existing = state.groups.firstOrNull { it != oldName && it.equals(requested, ignoreCase = true) }
            val target = existing ?: requested
            val configuredGroups = if (oldName in config.groups) {
                (config.groups - oldName + target).distinct().sorted()
            } else {
                config.groups
            }
            GeneralSourcePlan(workspace, GeneralSourceOperation.RENAME, oldName, target, existing != null,
                changesFor(state.files.filter { it.value == oldName }, target), raw,
                // A source value found only in a document stays inferred. Renaming one configured
                // group must not make every unrelated inferred value permanent configuration.
                config.copy(groups = configuredGroups))
        }
    }

    suspend fun planDelete(workspace: PlatformFile, name: String): GeneralSourcePlan = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireGeneral(workspace)
            val (raw, config) = readConfig(workspace)
            val state = loadUnlocked(workspace)
            check(state.enabled && name in state.groups) { "구분이 변경되었습니다. 목록을 새로고침해 주세요." }
            val remainingConfigured = config.groups - name
            val remainingGroups = if (remainingConfigured.isEmpty() && name in config.groups) {
                // Grouping is enabled by at least one configured group. When the last configured
                // group is removed, retain only the other source values that are still real groups;
                // otherwise grouping would switch off before the user can manage them.
                state.groups - name
            } else {
                remainingConfigured
            }
            GeneralSourcePlan(workspace, GeneralSourceOperation.DELETE, name, "", false,
                changesFor(state.files.filter { it.value == name }, ""), raw,
                config.copy(groups = remainingGroups))
        }
    }

    suspend fun planAssign(workspace: PlatformFile, files: List<ProjectFile>, target: String): GeneralSourcePlan = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireGeneral(workspace)
            val (raw, config) = readConfig(workspace)
            val state = loadUnlocked(workspace)
            check(state.enabled && target.isNotEmpty() && target in state.groups) { "실제 구분을 선택해 주세요." }
            val byKey = state.files.associateBy { it.file.key }
            val entries = files.distinctBy { it.key }.map { file ->
                val entry = byKey[file.key] ?: error("파일 위치가 변경되었습니다: ${file.key.relativePath}")
                check(entry.file.platformFile.toString() == file.platformFile.toString()) { "파일 위치가 변경되었습니다." }
                entry
            }
            GeneralSourcePlan(workspace, GeneralSourceOperation.ASSIGN, null, target, false,
                changesFor(entries, target), raw, config)
        }
    }

    /** The caller confirms rename/merge/delete before this method and pauses pending editor writes. */
    suspend fun apply(plan: GeneralSourcePlan): GeneralSourceResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireGeneral(plan.workspace)
            val failures = linkedMapOf<FileKey, String>()
            val changed = mutableListOf<ProjectFile>()
            // Do not overwrite a concurrent configuration change after the confirmation was prepared.
            val expectedConfig = if (plan.configWriteAttempted) plan.configFailureSnapshot else plan.originalConfig
            val currentConfig = readRawConfig(plan.workspace)
            val finalRaw = encodeConfigPreservingUnknown(plan.originalConfig, plan.finalConfig)
            if (currentConfig != expectedConfig && currentConfig != finalRaw) {
                return@withContext GeneralSourceResult(false, emptyList(), emptyMap(), safeLoad(plan.workspace), plan,
                    "구분 설정이 변경되었습니다. 내용을 확인하고 다시 시도해 주세요.")
            }
            for (change in plan.changes) {
                currentCoroutineContext().ensureActive()
                if (change.file.key in plan.completedFiles) {
                    changed += change.file
                    continue
                }
                try {
                    val file = change.file.platformFile
                    check(file.exists() && !file.isDirectory()) { "파일을 찾을 수 없습니다." }
                    val current = file.readString()
                    when (current) {
                        change.updated -> {
                            clearRecovery(plan.workspace, file, change.original)
                            plan.completedFiles += change.file.key
                            changed += change.file
                        }
                        plan.failedWriteSnapshots[change.file.key] ?: change.original -> {
                            try {
                                writeWithRecovery(plan.workspace, file, change.original, change.updated,
                                    plan.failedWriteSnapshots[change.file.key] ?: change.original)
                            } catch (error: Exception) {
                                withContext(NonCancellable) {
                                    runCatching { file.readString() }.getOrNull()?.let {
                                        plan.failedWriteSnapshots[change.file.key] = it
                                    }
                                }
                                throw error
                            }
                            changed += change.file
                            plan.completedFiles += change.file.key
                        }
                        else -> error("파일이 외부에서 변경되었거나 일부만 기록되었습니다. 덮어쓰지 않았습니다.")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures[change.file.key] = error.message ?: "source 기록 실패"
                }
            }
            metadataIndex.invalidate(
                plan.workspace,
                WorkspaceKind.GENERAL,
                plan.changes.map { it.file.key },
            )
            if (failures.isNotEmpty()) {
                return@withContext GeneralSourceResult(false, changed, failures, safeLoad(plan.workspace), plan)
            }
            try {
                if (currentConfig != finalRaw && plan.kind != GeneralSourceOperation.ASSIGN &&
                    !(plan.kind == GeneralSourceOperation.RENAME && plan.oldName == plan.targetName)) {
                    saveConfig(plan.workspace, expectedConfig, plan.finalConfig, plan.originalConfig) { snapshot ->
                        plan.configWriteAttempted = true
                        plan.configFailureSnapshot = snapshot
                    }
                }
                if (currentConfig == finalRaw) {
                    plan.workspace.list().firstOrNull { it.name == GENERAL_SOURCE_CONFIG }?.let {
                        clearRecovery(plan.workspace, it, plan.originalConfig)
                    }
                }
                GeneralSourceResult(true, changed, emptyMap(), loadUnlocked(plan.workspace), plan)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                GeneralSourceResult(false, changed, emptyMap(), safeLoad(plan.workspace), plan,
                    "파일 적용 후 구분 설정 저장 실패: ${error.message}")
            }
        }
    }

    private suspend fun changesFor(entries: List<GeneralSourceEntry>, target: String): List<GeneralSourceChange> =
        entries.mapNotNull { entry ->
            check(entry.error == null) { "${entry.file.key.relativePath}: ${entry.error}" }
            val original = entry.file.platformFile.readString()
            val currentSource = GeneralSourceProperty.read(original)
            check(currentSource.error == null && currentSource.value == entry.value) {
                "${entry.file.key.relativePath}: source가 변경되었습니다. 다시 시도해 주세요."
            }
            val updated = GeneralSourceProperty.write(original, target)
            if (original == updated) null else GeneralSourceChange(entry.file, original, updated)
        }

    private suspend fun scan(
        workspace: PlatformFile,
        files: List<ProjectFile>? = null,
    ): List<GeneralSourceEntry> {
        val trace = WorkspaceLoadDiagnostics.begin(
            "general-source-scan",
            "providedFiles=${files?.size ?: -1}",
        )
        try {
        // Callers that just built the hierarchy already paid the SAF provider cost. Reuse that
        // immutable snapshot instead of listing every folder and file a second time.
        val scanFiles = files ?: manager.listFolders(workspace).flatMap { manager.listProjectFiles(it) }
        val snapshot = metadataIndex.snapshot(workspace, WorkspaceKind.GENERAL)
            ?: throw CancellationException("작업 공간이 변경되었습니다.")
        val cached = snapshot.entries
        val permits = Semaphore(GENERAL_SOURCE_SCAN_PARALLELISM)
        val indexed = coroutineScope {
            scanFiles.map { file ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        currentCoroutineContext().ensureActive()
                        val modifiedAt = manager.lastModified(file.platformFile)
                        val previous = cached[file.key]
                        val source = previous
                            ?.takeIf { it.isFresh(file, modifiedAt) }
                            ?.source
                            ?.takeIf(IndexedGeneralSource::readSucceeded)
                            ?: try {
                                GeneralSourceProperty.read(readSource(file.platformFile)).let {
                                    IndexedGeneralSource(it.value, it.error)
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                IndexedGeneralSource(
                                    value = null,
                                    error = error.message ?: "파일 읽기 실패",
                                    readSucceeded = false,
                                )
                            }
                        WorkspaceFileMetadata(
                            file = file,
                            modifiedAt = modifiedAt,
                            source = source,
                            plot = previous?.takeIf { it.isFresh(file, modifiedAt) }?.plot,
                        )
                    }
                }
            }.awaitAll()
        }
        val reconciled = metadataIndex.reconcileAll(
            workspace,
            WorkspaceKind.GENERAL,
            snapshot,
            indexed,
        )
        val entries = scanFiles.mapNotNull { reconciled[it.key] }.map { item ->
            val source = checkNotNull(item.source)
            GeneralSourceEntry(item.file, source.value, source.error)
        }
        val reused = indexed.count { item -> cached[item.key]?.source === item.source }
        trace.complete(
            "files=${scanFiles.size}|cached=$reused|read=${scanFiles.size - reused}|entries=${entries.size}",
        )
        return entries
        } catch (error: Exception) {
            trace.fail(error)
            throw error
        }
    }

    private suspend fun loadUnlocked(
        workspace: PlatformFile,
        files: List<ProjectFile>? = null,
    ): GeneralSourceState {
        val trace = WorkspaceLoadDiagnostics.begin(
            "general-source-load",
            "providedFiles=${files?.size ?: -1}",
        )
        try {
        check(workspace.list().none { it.name.startsWith(GENERAL_SOURCE_RECOVERY_PREFIX) }) {
            "이전 source 기록의 복구 자료가 남아 있습니다. .machum-general-recovery- 파일의 원문을 확인해 주세요."
        }
        val (_, config) = readConfig(workspace)
        val scannedFiles = scan(workspace, files)
        val enabled = config.groups.isNotEmpty()
        val state = GeneralSourceState(
            if (enabled) (config.groups + scannedFiles.mapNotNull { it.value?.takeIf(String::isNotEmpty) }).distinct().sorted() else emptyList(),
            scannedFiles,
            enabled,
            config.propertyTypes,
            config.defaultPropertyKeys,
            config.groups.sorted(),
        )
        trace.complete(
            "enabled=$enabled|configured=${config.groups.size}|groups=${state.groups.size}|entries=${state.files.size}",
        )
        return state
        } catch (error: Exception) {
            trace.fail(error)
            throw error
        }
    }

    private suspend fun safeLoad(workspace: PlatformFile): GeneralSourceState? = runCatching { loadUnlocked(workspace) }.getOrNull()

    private fun validName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.none(Char::isISOControl)) { "구분 이름을 입력해 주세요." }
    }

    private fun requireGeneral(workspace: PlatformFile) {
        val selected = manager.bookmarks.value
        check(selected.workspaceKind == WorkspaceKind.GENERAL && selected.projectData?.toString() == workspace.toString()) {
            "선택한 General 작업 공간에서만 구분을 변경할 수 있습니다."
        }
    }

    private suspend fun readRawConfig(workspace: PlatformFile): String? {
        val file = workspace.list().firstOrNull { it.name == GENERAL_SOURCE_CONFIG } ?: return null
        check(!file.isDirectory()) { "$GENERAL_SOURCE_CONFIG 경로가 파일이 아닙니다." }
        return file.readString()
    }

    private suspend fun readConfig(workspace: PlatformFile): Pair<String?, GeneralSourceConfig> {
        val raw = readRawConfig(workspace) ?: return null to GeneralSourceConfig()
        val config = json.decodeFromString<GeneralSourceConfig>(raw)
        check(
            config.version == 1 &&
                config.groups.all { it.isNotBlank() } &&
                config.groups.distinct().size == config.groups.size &&
                config.defaultPropertyKeys.all { it.isNotBlank() } &&
                config.defaultPropertyKeys.all { it == it.trim() } &&
                config.defaultPropertyKeys.distinct().size == config.defaultPropertyKeys.size &&
                config.defaultPropertyKeys.all { documentPropertyKeyError(it) == null } &&
                config.propertyTypes.keys.all { it.isNotBlank() && it == it.trim() } &&
                config.propertyTypes.keys.all { documentPropertyKeyError(it) == null } &&
                config.propertyTypes.values.none { it == DocumentPropertyType.UNSUPPORTED },
        ) { "구분 설정이 손상되었습니다. 원본을 보존했습니다." }
        return raw to config
    }

    private suspend fun saveConfig(
        workspace: PlatformFile,
        expected: String?,
        config: GeneralSourceConfig,
        originalForRecovery: String? = expected,
        onWriteFailure: (String?) -> Unit = {},
    ) = withContext(NonCancellable) {
        check(readRawConfig(workspace) == expected) { "구분 설정이 외부에서 변경되었습니다." }
        val file = manager.setConfig(workspace, GENERAL_SOURCE_CONFIG) ?: error("구분 설정 파일을 만들 수 없습니다.")
        val updated = encodeConfigPreservingUnknown(originalForRecovery ?: expected, config)
        withContext(NonCancellable) {
            try {
                writeWithRecovery(workspace, file, originalForRecovery, updated, expected)
            } catch (error: Exception) {
                onWriteFailure(runCatching { readRawConfig(workspace) }.getOrNull())
                throw error
            }
        }
    }

    private fun encodeConfigPreservingUnknown(raw: String?, updated: GeneralSourceConfig): String {
        val updatedKnown = json.encodeToString(updated)
        if (raw == null) return updatedKnown
        val previous = json.decodeFromString<GeneralSourceConfig>(raw)
        return mergeKnownConfig(raw, json.encodeToString(previous), updatedKnown)
    }

    /** Persist the original before touching a target, including across process termination. */
    private suspend fun writeWithRecovery(
        workspace: PlatformFile,
        file: PlatformFile,
        original: String?,
        updated: String,
        expected: String? = original,
    ) = withContext(NonCancellable) {
        var name: String
        do {
            name = "$GENERAL_SOURCE_RECOVERY_PREFIX${Random.nextLong().toString(16)}.json"
        } while (workspace.list().any { it.name == name })
        val recoveryRaw = json.encodeToString(GeneralSourceRecovery(file.toString(), original))
        val recovery = try {
            val created = manager.setConfig(workspace, name) ?: error("원문 복구 자료를 만들 수 없어 기록하지 않았습니다.")
            writeRecovery(created, recoveryRaw)
            check(created.readString() == recoveryRaw) { "원문 복구 자료를 확인할 수 없어 기록하지 않았습니다: $name" }
            created
        } catch (failure: Exception) {
            // No target write was attempted. Remove only this new journal and an empty new config.
            runCatching {
                workspace.list().firstOrNull { it.name == name }?.delete()
                if (original == null && file.exists() && file.readString().isEmpty()) file.delete()
            }.onFailure(failure::addSuppressed)
            throw failure
        }
        val beforeWrite = file.readString()
        if (beforeWrite != (expected ?: "")) {
            recovery.delete()
            error("복구 자료 준비 중 대상이 변경되었습니다. 덮어쓰지 않았습니다.")
        }
        try {
            write(file, updated)
            check(file.readString() == updated) { "기록 결과를 확인할 수 없습니다." }
        } catch (failure: Exception) {
            val observed = runCatching { if (file.exists()) file.readString() else null }
            val restored = runCatching {
                val snapshot = observed.getOrThrow()
                check((if (file.exists()) file.readString() else null) == snapshot) {
                    "기록 실패 후 파일이 변경되어 원문 복구를 중단했습니다."
                }
                if (snapshot == original) {
                    // The writer failed without changing the target.
                } else if (original == null) {
                    if (file.exists()) file.delete()
                    check(!file.exists()) { "처음 만든 설정 파일을 제거하지 못했습니다." }
                } else {
                    write(file, original)
                    check(file.readString() == original) { "원문 복구 결과를 확인할 수 없습니다." }
                }
            }
            if (restored.isSuccess) {
                recovery.delete()
                clearRecovery(workspace, file, original)
                throw failure
            }
            throw IllegalStateException("source 기록과 원문 복구에 실패했습니다. 복구 원문: $name. ${restored.exceptionOrNull()?.message}", failure)
        }
        recovery.delete()
        clearRecovery(workspace, file, original)
    }

    private suspend fun clearRecovery(workspace: PlatformFile, file: PlatformFile, original: String?) {
        workspace.list().filter { candidate ->
            candidate.name.startsWith(GENERAL_SOURCE_RECOVERY_PREFIX) && runCatching {
                json.decodeFromString<GeneralSourceRecovery>(candidate.readString()) ==
                    GeneralSourceRecovery(file.toString(), original)
            }.getOrDefault(false)
        }.forEach { it.delete() }
    }
}
