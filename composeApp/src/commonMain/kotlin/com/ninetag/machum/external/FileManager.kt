package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
import com.ninetag.machum.entity.DEFAULT_PROJECT_FOLDERS
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.defaultProjectConfig
import com.ninetag.machum.entity.normalizeTag
import com.ninetag.machum.entity.renameFolder
import com.ninetag.machum.entity.removeFolder
import com.ninetag.machum.entity.withDefaultBaseFolder
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.bookmarkData
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.fromBookmarkData
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.collections.emptyList

class FileManager(private val dataStore: DataStore<Preferences>) {

    val generalSources = GeneralSourceService(this)

    private val projectConfigMutex = Mutex()
    private val vaultConfigMutex = Mutex()
    private val projectIndexer = ProjectIndexer(this)
    private val workspaceOpenMutex = Mutex()
    private val workspaceTrashMutex = Mutex()
    internal var workspaceTrashClock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    internal var workspaceTrashPurger: suspend (PlatformFile, PlatformFile) -> Unit = ::purgeWorkspaceTrashEntryNative
    private val _workspaceTrashWarning = MutableStateFlow<String?>(null)
    val workspaceTrashWarning = _workspaceTrashWarning.asStateFlow()
    private var initialized = false
    private val _workspaceOpenRequest = MutableStateFlow<WorkspaceOpenRequest?>(null)
    val workspaceOpenRequest = _workspaceOpenRequest.asStateFlow()

    val projectIndexState: StateFlow<ProjectIndexState> = projectIndexer.state

    // .machum.json 직렬화: 구 스키마(workflow 필드 등) 무시 + 사람이 읽기 좋게 + 기본값도 기록
    private val configJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    companion object {
        private const val PROJECT_CONFIG_FILE_NAME = ".machum.json"
        private const val VAULT_CONFIG_FILE_NAME = ".machum-vault.json"
        private const val FOLDER_ID_FILE_NAME = ".machum-folder-id.json"
        private val BOOKMARK_VAULT = byteArrayPreferencesKey("bookmark_vault")
        private val BOOKMARK_PROJECT = byteArrayPreferencesKey("bookmark_project")
        private val BOOKMARK_FILE = stringPreferencesKey("bookmark_file")
        // `.machum-vault.json` 도입 전 절대 경로 분류를 현재 Vault로 한 번 옮기기 위한 키.
        private val GENERAL_FOLDERS = stringSetPreferencesKey("general_folders")
        private val WORKSPACE_KIND = stringPreferencesKey("workspace_kind")
    }

    // ToDo 테스트 이후 private 으로 변경
    suspend fun setPreferences(bookmark: Bookmarks): Bookmarks {
        val normalizedBookmark = bookmark.copy(
            fileRelativePath = bookmark.fileData?.let { bookmark.fileRelativePath ?: it.name },
        )
        dataStore.edit { pref ->
            pref[WORKSPACE_KIND] = normalizedBookmark.workspaceKind.name
            normalizedBookmark.vaultData?.let { pref[BOOKMARK_VAULT] = it.bookmarkData().bytes }
            normalizedBookmark.projectData?.let { pref[BOOKMARK_PROJECT] = it.bookmarkData().bytes }?:pref.remove(BOOKMARK_PROJECT)
            normalizedBookmark.fileData
                ?.let { pref[BOOKMARK_FILE] = normalizedBookmark.fileRelativePath ?: it.name }
                ?: pref.remove(BOOKMARK_FILE)
        }
        if (!sameLocation(_bookmarks.value.projectData, normalizedBookmark.projectData)) {
            _projectConfig.value = null
        }
        _bookmarks.value = normalizedBookmark
        return normalizedBookmark
    }

    // ToDo 테스트 이후 private 으로 변경
    suspend fun getPreferences(): Bookmarks {
        return dataStore.data.first().let{ pref ->
            val vault = pref[BOOKMARK_VAULT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) }
            val project = pref[BOOKMARK_PROJECT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) }
            val fileRelativePath = pref[BOOKMARK_FILE]
            val file = project?.let { root ->
                fileRelativePath?.let { resolveRelativeFile(root, it) }
            }
            Bookmarks(
                vaultData = vault,
                projectData = project,
                fileData = file,
                fileRelativePath = fileRelativePath,
                workspaceKind = if (pref[WORKSPACE_KIND] == WorkspaceKind.GENERAL.name) {
                    WorkspaceKind.GENERAL
                } else WorkspaceKind.PROJECT,
            )
        }
    }

    /** 앱이 보관한 선택 상태를 초기화한다. Vault 안의 실제 파일은 삭제하지 않는다. */
    suspend fun reset() {
        dataStore.edit { pref ->
            pref.remove(BOOKMARK_VAULT)
            pref.remove(BOOKMARK_PROJECT)
            pref.remove(BOOKMARK_FILE)
            pref.remove(WORKSPACE_KIND)
        }
        _bookmarks.value = Bookmarks()
        _projectConfig.value = null
        projectIndexer.reset()
        _workspaceOpenRequest.value = null
    }

    private val _bookmarks = MutableStateFlow(Bookmarks())
    val bookmarks: StateFlow<Bookmarks> = _bookmarks.asStateFlow()

    /** 일반 폴더에서는 파일 탐색 재사용을 위한 General 설정을 메모리에만 보관한다. */
    private val _projectConfig = MutableStateFlow<ProjectConfig?>(null)
    val projectConfig: StateFlow<ProjectConfig?> = _projectConfig.asStateFlow()

    /** App이 소유한 coroutine에서 선택 상태를 한 번 복원한다. 실패/취소 시 재시도할 수 있다. */
    suspend fun initialize() = workspaceOpenMutex.withLock {
        if (initialized) return@withLock
        try {
            withContext(Dispatchers.IO) {
                validateVault()?.let { vault ->
                    maintainWorkspaceTrash(vault)
                    validProject()
                }
                currentCoroutineContext().ensureActive()
            }
            initialized = true
        } catch (error: Exception) {
            // 저장된 선택 정보는 유지하되 중간에 공개한 상태는 재시도 전에 비운다.
            _bookmarks.value = Bookmarks()
            _projectConfig.value = null
            projectIndexer.reset()
            _workspaceOpenRequest.value = null
            throw error
        }
    }

    private suspend fun validateVault(): PlatformFile? {
        val vault = getPreferences().vaultData ?: return null
        if (!validPermission(vault)) {
            reset()
            return null
        }
        val bookmark = Bookmarks(vaultData = vault)
        _bookmarks.value = bookmark
        _projectConfig.value = null
        return vault
    }

    private suspend fun validProject(): PlatformFile? {
        val bookmarks = getPreferences()
        val project = bookmarks.projectData ?: return null
        when (workspaceSetup(project)) {
            WorkspaceSetup.GENERAL -> {
                openGeneralFolder(project, bookmarks)
                return project
            }
            WorkspaceSetup.NEEDS_CONFIRMATION -> {
                _workspaceOpenRequest.value = WorkspaceOpenRequest(project)
                return null
            }
            WorkspaceSetup.PROJECT -> Unit
        }
        projectIndexer.prepare(project)
        _bookmarks.value = bookmarks.copy(workspaceKind = WorkspaceKind.PROJECT)
        checkNotNull(loadProjectConfig(project)) { "프로젝트 설정을 불러오지 못했습니다." }
        bookmarks.vaultData?.let { vault ->
            runCatching { rememberProject(vault, project) }
                .onFailure { if (it is CancellationException) throw it }
        }
        return project
    }

    private suspend fun hasValidProjectConfig(directory: PlatformFile): Boolean {
        val configFile = directory.list().find { it.name == PROJECT_CONFIG_FILE_NAME } ?: return false
        check(!configFile.isDirectory()) { "프로젝트 설정 경로가 디렉터리입니다." }
        return readConfig(configFile) != null
    }

    private fun validVaultChildName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name

    private fun VaultConfig.normalized(): VaultConfig = copy(
        generalFolders = generalFolders
            .filter(::validVaultChildName)
            .distinct()
            .sorted(),
        lastProject = lastProject?.takeIf(::validVaultChildName),
        suspendedProjects = suspendedProjects.filterKeys(::validVaultChildName),
    )

    /** 파일이 없으면 생성하지 않고 빈 설정으로 읽는다. 손상된 파일은 조용히 덮어쓰지 않는다. */
    private suspend fun readVaultConfigUnlocked(vault: PlatformFile): VaultConfig {
        val file = vault.list().find { it.name == VAULT_CONFIG_FILE_NAME } ?: return VaultConfig()
        check(!file.isDirectory()) { "Vault 설정 경로가 디렉터리입니다." }
        val content = file.readString()
        check(content.isNotBlank()) { "Vault 설정 파일이 비어 있습니다." }
        return try {
            configJson.decodeFromString(VaultConfig.serializer(), content).normalized()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            throw IllegalStateException("Vault 설정 파일을 읽을 수 없습니다.", error)
        }
    }

    private suspend fun persistVaultConfigUnlocked(vault: PlatformFile, config: VaultConfig): VaultConfig {
        val normalized = config.normalized()
        val file = setConfig(vault, VAULT_CONFIG_FILE_NAME)
            ?: error("Vault 설정을 저장하지 못했습니다.")
        file.writeString(configJson.encodeToString(VaultConfig.serializer(), normalized))
        return normalized
    }

    private suspend fun updateVaultConfig(
        vault: PlatformFile,
        transform: (VaultConfig) -> VaultConfig,
    ): VaultConfig = vaultConfigMutex.withLock {
        val current = readVaultConfigUnlocked(vault)
        val updated = transform(current).normalized()
        if (updated == current) current else persistVaultConfigUnlocked(vault, updated)
    }

    // Dedicated to the new selection-screen mutations; legacy Vault writes keep their contract.
    internal var workspaceVaultConfigWriter: suspend (PlatformFile, String) -> Unit = { file, text ->
        file.writeString(text)
    }

    private suspend fun updateWorkspaceVaultConfig(
        vault: PlatformFile,
        transform: (VaultConfig) -> VaultConfig,
    ): VaultConfig = vaultConfigMutex.withLock {
        val originalFile = vault.list().firstOrNull { it.name == VAULT_CONFIG_FILE_NAME }
        check(originalFile == null || !originalFile.isDirectory()) { "Vault 설정 경로가 파일이 아닙니다." }
        val originalText = originalFile?.readString()
        val original = originalText?.let { configJson.decodeFromString(VaultConfig.serializer(), it) }
            ?: VaultConfig()
        val updated = transform(original).normalized()
        if (updated == original) return@withLock original
        val updatedText = configJson.encodeToString(VaultConfig.serializer(), updated)
        val file = originalFile ?: setConfig(vault, VAULT_CONFIG_FILE_NAME)
            ?: error("Vault 설정을 만들지 못했습니다.")
        val expectedBefore = originalText ?: ""
        check(file.readString() == expectedBefore) { "Vault 설정이 외부에서 변경되었습니다. 다시 시도해 주세요." }
        try {
            workspaceVaultConfigWriter(file, updatedText)
            check(file.readString() == updatedText) { "Vault 설정 기록 결과가 요청과 다릅니다." }
        } catch (failure: Exception) {
            // Only compensate unchanged original bytes or a recognizable incomplete write.
            // Unknown bytes can belong to an external writer and must not be overwritten.
            // FileKit/SAF supplies no atomic compare-and-swap: an external write after the
            // final comparison remains a platform limitation, including identical prefixes.
            val observed = runCatching { file.readString() }.getOrNull()
            val ownWrite = observed != null && updatedText.startsWith(observed)
            val restored = if (observed == originalText) true else if (ownWrite) {
                runCatching {
                    check(file.readString() == observed) { "복구 직전 Vault 설정이 외부에서 변경되었습니다." }
                    if (originalText == null) {
                        file.delete()
                        check(!file.exists()) { "새 Vault 설정 파일을 정리하지 못했습니다." }
                    } else {
                        workspaceVaultConfigWriter(file, originalText)
                        check(file.readString() == originalText) { "Vault 설정 복구 결과가 원문과 다릅니다." }
                    }
                }.onFailure { failure.addSuppressed(it) }.isSuccess
            } else false
            throw IllegalStateException(
                if (restored) "Vault 설정 기록에 실패했으며 이전 설정을 복구했습니다."
                else "Vault 설정 기록/복구를 완료하지 못했습니다. 외부 변경은 덮어쓰지 않았습니다. 설정 파일을 확인해 주세요: $file",
                failure,
            )
        }
        updated
    }

    /**
     * 과거 DataStore의 절대 경로 중 현재 Vault 직속 항목과 정확히 일치하는 값만 한 번 옮긴다.
     * 다른 Vault의 값이나 경로를 추측해서 합치지 않는다.
     */
    private suspend fun loadVaultConfig(
        vault: PlatformFile,
        directories: List<PlatformFile>,
    ): VaultConfig = vaultConfigMutex.withLock {
        val current = try {
            readVaultConfigUnlocked(vault)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Project 접근까지 막지 않는다. 손상 파일은 그대로 두고 일반 폴더만 미설정으로 취급한다.
            return@withLock VaultConfig()
        }
        val legacy = dataStore.data.first()[GENERAL_FOLDERS].orEmpty()
        val matched = directories.filter { it.toString() in legacy }
        if (matched.isEmpty()) return@withLock current

        val generalNames = matched
            .filterNot { directory ->
                runCatching {
                    directory.list().any { it.name == PROJECT_CONFIG_FILE_NAME }
                }.getOrDefault(true)
            }
            .map(PlatformFile::name)
        val updated = current.copy(
            generalFolders = current.generalFolders + generalNames,
        ).normalized()
        val persisted = if (updated == current) current else persistVaultConfigUnlocked(vault, updated)
        val consumed = matched.mapTo(mutableSetOf(), PlatformFile::toString)
        dataStore.edit { preferences ->
            val remaining = preferences[GENERAL_FOLDERS].orEmpty() - consumed
            if (remaining.isEmpty()) preferences.remove(GENERAL_FOLDERS)
            else preferences[GENERAL_FOLDERS] = remaining
        }
        persisted
    }

    private suspend fun currentVaultAndDirectories(): Pair<PlatformFile, List<PlatformFile>> {
        val vault = getPreferences().vaultData ?: error("선택된 Vault가 없습니다.")
        return vault to listProject(vault)
    }

    private fun requireVaultChild(
        directory: PlatformFile,
        directories: List<PlatformFile>,
    ): PlatformFile = directories.firstOrNull { sameLocation(it, directory) }
        ?: error("선택한 폴더가 현재 Vault에 없습니다.")

    private suspend fun rememberProject(
        vault: PlatformFile,
        project: PlatformFile,
        previousName: String? = null,
    ) {
        val child = requireVaultChild(project, listProject(vault))
        updateVaultConfig(vault) { config ->
            config.copy(
                generalFolders = config.generalFolders - listOfNotNull(previousName, child.name).toSet(),
                lastProject = child.name,
            )
        }
    }

    /** 선택 전 판별. Markdown은 비변경; 최초 legacy DataStore 분류 이전만 Vault 설정을 기록할 수 있다. */
    suspend fun workspaceSetup(directory: PlatformFile): WorkspaceSetup = withContext(Dispatchers.IO) {
        val (vault, directories) = currentVaultAndDirectories()
        val child = requireVaultChild(directory, directories)
        loadVaultConfig(vault, directories) // Preserve the one-time legacy classification migration.
        val config = vaultConfigMutex.withLock { readVaultConfigUnlocked(vault) }
        if (suspendedProject(child, config) != null) return@withContext WorkspaceSetup.GENERAL
        if (hasValidProjectConfig(child)) WorkspaceSetup.PROJECT
        else if (child.name in config.generalFolders) WorkspaceSetup.GENERAL
        else WorkspaceSetup.NEEDS_CONFIRMATION
    }

    suspend fun hasSuspendedProject(vault: PlatformFile, directory: PlatformFile): Boolean =
        workspaceOpenMutex.withLock {
            withContext(Dispatchers.IO) {
                requireSelectedVault(vault)
                val child = requireVaultChild(directory, listProject(vault))
                suspendedProject(child, vaultConfigMutex.withLock { readVaultConfigUnlocked(vault) }) != null
            }
        }

    /** Portable root identity keeps external workspace renames in General mode. Never guess by name. */
    private suspend fun suspendedProject(directory: PlatformFile, config: VaultConfig): Map.Entry<String, SuspendedProjectSettings>? {
        val marker = directory.list().firstOrNull { it.name == FOLDER_ID_FILE_NAME }
        val id = marker?.let { folderIdentity(it) }
        val matches = config.suspendedProjects.entries.filter { it.value.workspaceIdentity == id }
        check(matches.size <= 1) { "중복 작업 공간 보관 설정이 있습니다." }
        val matched = matches.singleOrNull()
        check(config.suspendedProjects[directory.name] == null || matched?.key == directory.name) {
            "같은 이름의 작업 공간이 교체되었거나 식별 설정이 없습니다. 보관 설정을 확인해 주세요."
        }
        return matched
    }

    private suspend fun checkUniqueWorkspaceIdentity(vault: PlatformFile, identity: String) {
        val matching = vault.list().filter { it.isDirectory() && !it.name.startsWith(".") }.count { directory ->
            directory.list().firstOrNull { it.name == FOLDER_ID_FILE_NAME }?.let { folderIdentity(it) == identity } == true
        }
        check(matching == 1) { "같은 식별 설정을 가진 작업 공간이 여러 개입니다. 전환하지 않았습니다." }
    }

    private fun requireSelectedVault(vault: PlatformFile) {
        check(_bookmarks.value.vaultData?.let { sameLocation(it, vault) } == true) {
            "Vault가 변경되었습니다. 목록을 다시 열어 주세요."
        }
    }

    private suspend fun folderIdentity(file: PlatformFile): String {
        check(!file.isDirectory() && file.name == FOLDER_ID_FILE_NAME) { "폴더 식별 설정 경로가 올바르지 않습니다." }
        val id = configJson.decodeFromString(WorkspaceFolderIdentity.serializer(), file.readString()).machumFolderId
        check(id.matches(Regex("[a-f0-9]{32}"))) { "폴더 식별 설정이 손상되었습니다." }
        return id
    }

    internal suspend fun isManagedFolderIdentity(file: PlatformFile): Boolean =
        file.name == FOLDER_ID_FILE_NAME && runCatching { folderIdentity(file) }.isSuccess

    internal var workspaceTransitionWriter: suspend (PlatformFile, String) -> Unit = { file, text -> file.writeString(text) }

    /** Prepared hidden identities remain reusable if a later mode write fails. */
    private suspend fun ensureFolderIdentity(folder: PlatformFile): String {
        folder.list().firstOrNull { it.name == FOLDER_ID_FILE_NAME }?.let { return folderIdentity(it) }
        val id = buildString { repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) } }
        val text = configJson.encodeToString(WorkspaceFolderIdentity.serializer(), WorkspaceFolderIdentity(id))
        val file = setConfig(folder, FOLDER_ID_FILE_NAME) ?: error("폴더 식별 설정을 만들지 못했습니다.")
        try {
            check(file.name == FOLDER_ID_FILE_NAME && file.readString().isEmpty()) { "폴더 식별 설정 경로가 충돌합니다." }
            workspaceTransitionWriter(file, text)
            check(file.readString() == text) { "폴더 식별 설정 기록 결과가 다릅니다." }
        } catch (failure: Exception) {
            val observed = runCatching { file.readString() }.getOrNull()
            if (observed != null && text.startsWith(observed)) {
                runCatching { if (file.readString() == observed) file.delete() }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
        return id
    }

    /** Call under MainViewModel.runWorkspaceSelectionAction save fence; bookmarks remain ordinary reopening data. */
    suspend fun transitionWorkspace(
        vault: PlatformFile,
        directory: PlatformFile,
        expectedSetup: WorkspaceSetup,
        targetKind: WorkspaceKind,
    ): Unit = workspaceOpenMutex.withLock {
        withContext(Dispatchers.IO) {
            requireSelectedVault(vault)
            val child = requireVaultChild(directory, listProject(vault))
            check(workspaceSetup(child) == expectedSetup) { "작업 공간 분류가 변경되었습니다. 목록을 새로고침해 주세요." }
            check(expectedSetup != WorkspaceSetup.NEEDS_CONFIRMATION) { "미설정 폴더는 먼저 사용 방식을 선택해 주세요." }
            check((targetKind == WorkspaceKind.GENERAL) == (expectedSetup == WorkspaceSetup.PROJECT)) { "이미 요청한 분류입니다." }
            val before = vaultConfigMutex.withLock { readVaultConfigUnlocked(vault) }
            projectConfigMutex.withLock {
                withContext(NonCancellable) {
                    if (targetKind == WorkspaceKind.GENERAL) {
                        val configFile = child.list().firstOrNull { it.name == PROJECT_CONFIG_FILE_NAME } ?: error("프로젝트 설정이 없습니다.")
                        val originalText = configFile.readString()
                        val config = checkNotNull(readConfig(configFile)) { "프로젝트 설정이 비어 있습니다." }
                        val workspaceIdentity = ensureFolderIdentity(child)
                        checkUniqueWorkspaceIdentity(vault, workspaceIdentity)
                        val identities = linkedMapOf<String, FolderConfig>()
                        listFolders(child).filter { it.key != FolderKey.Base }.forEach { folder ->
                            val id = ensureFolderIdentity(folder.platformFile)
                            check(id !in identities) { "중복 폴더 식별 설정이 있습니다. 전환하지 않았습니다." }
                            identities[id] = config.folders[folder.key.relativePath] ?: FolderConfig()
                        }
                        check(configFile.readString() == originalText) { "프로젝트 설정이 외부에서 변경되었습니다." }
                        updateWorkspaceVaultConfig(vault) { current ->
                            check(current == before) { "Vault 설정이 외부에서 변경되었습니다." }
                            current.copy(generalFolders = current.generalFolders + child.name,
                                lastProject = current.lastProject.takeUnless { it == child.name },
                                suspendedProjects = current.suspendedProjects + (child.name to
                                    SuspendedProjectSettings(child.name, workspaceIdentity, originalText, identities)))
                        }
                    } else resumeWorkspaceProject(vault, child, before)
                }
            }
        }
    }

    private data class WorkspaceFileChange(val file: PlatformFile, val before: String, val after: String)

    private suspend fun resumeWorkspaceProject(vault: PlatformFile, child: PlatformFile, vaultBefore: VaultConfig) {
        val suspendedEntry = suspendedProject(child, vaultBefore)
        val suspended = suspendedEntry?.value
        suspended?.let { checkUniqueWorkspaceIdentity(vault, it.workspaceIdentity) }
        val initialEntries = child.list()
        val priorConfigFile = initialEntries.firstOrNull { it.name == PROJECT_CONFIG_FILE_NAME }
        val changes = mutableListOf<WorkspaceFileChange>()
        var initialized = false
        try {
            if (suspended == null) {
                check(priorConfigFile == null) { "프로젝트 설정이 외부에서 생겼습니다. 목록을 다시 열어 주세요." }
                configureExistingProject(child)
                initialized = true
            }
            val configFile = child.list().first { it.name == PROJECT_CONFIG_FILE_NAME }
            val storedText = configFile.readString()
            val config = checkNotNull(readConfig(configFile)) { "프로젝트 설정이 비어 있습니다." }
            var resumedConfig = config
            if (suspended != null) {
                check(storedText == suspended.originalConfigText) { "보관 중인 프로젝트 설정이 외부에서 변경되었습니다. 설정을 확인해 주세요." }
                val seen = mutableSetOf<String>()
                val folders = linkedMapOf(BASE_FOLDER_PATH to (config.folders[BASE_FOLDER_PATH] ?: DEFAULT_BASE_FOLDER_CONFIG))
                listFolders(child).filter { it.key != FolderKey.Base }.forEach { folder ->
                    val marker = folder.platformFile.list().firstOrNull { it.name == FOLDER_ID_FILE_NAME }
                    val id = marker?.let { folderIdentity(it) }
                    check(id == null || seen.add(id)) { "중복 폴더 식별 설정이 있습니다. 복귀하지 않았습니다." }
                    folders[folder.key.relativePath] = id?.let { suspended.foldersByIdentity[it] } ?: FolderConfig(type = FolderType.GENERAL)
                }
                resumedConfig = config.copy(folders = folders)
            }
            val previousTag = normalizeTag(suspended?.projectName ?: child.name)
            val projectTag = normalizeTag(child.name)
            val fileIds = linkedMapOf<String, String>()
            listFolders(child).flatMap { listProjectFiles(it) }.forEach { projectFile ->
                val file = projectFile.platformFile
                val text = file.readString()
                val note = NoteFile.parse(text)
                val updated = note.ensureId().withTags(buildList {
                    add(projectTag)
                    note.tags.filterNot { it == previousTag || it == projectTag }.forEach { if (it !in this) add(it) }
                })
                val id = checkNotNull(updated.id)
                check(id !in fileIds) { "중복 문서 ID가 있습니다. 프로젝트로 전환하지 않았습니다: ${projectFile.key.relativePath}" }
                fileIds[id] = projectFile.key.relativePath
                if (updated.inject() != note.inject()) changes += WorkspaceFileChange(file, text, updated.inject())
            }
            // Frontmatter is authoritative, including General-time renames, removals and new files.
            val updatedConfig = resumedConfig.copy(fileIds = fileIds)
            if (updatedConfig != config) {
                changes.add(0, WorkspaceFileChange(configFile, storedText,
                    configJson.encodeToString(ProjectConfig.serializer(), updatedConfig)))
            }
            val attempted = mutableListOf<WorkspaceFileChange>()
            try {
                changes.forEach { change ->
                    check(change.file.readString() == change.before) { "전환 중 파일이 외부에서 변경되었습니다: ${change.file.name}" }
                    attempted += change
                    workspaceTransitionWriter(change.file, change.after)
                    check(change.file.readString() == change.after) { "전환 기록 결과가 요청과 다릅니다: ${change.file.name}" }
                }
                changes.forEach { check(it.file.readString() == it.after) { "전환 기록 후 외부 변경이 발견되었습니다." } }
                updateWorkspaceVaultConfig(vault) { current ->
                    check(current == vaultBefore) { "Vault 설정이 외부에서 변경되었습니다." }
                    current.copy(generalFolders = current.generalFolders - setOfNotNull(child.name, suspendedEntry?.key),
                        suspendedProjects = current.suspendedProjects - setOfNotNull(child.name, suspendedEntry?.key))
                }
            } catch (failure: Exception) {
                attempted.asReversed().forEach { change ->
                    runCatching {
                        val observed = change.file.readString()
                        if (observed != change.before) {
                            check(change.after.startsWith(observed)) { "외부 변경은 덮어쓰지 않았습니다: ${change.file.name}" }
                            check(change.file.readString() == observed) { "복구 직전 외부 변경이 발견되었습니다." }
                            workspaceTransitionWriter(change.file, change.before)
                            check(change.file.readString() == change.before) { "전환 기록 복구에 실패했습니다." }
                        }
                    }.onFailure(failure::addSuppressed)
                }
                throw IllegalStateException("프로젝트 전환을 완료하지 못했습니다. ${failure.message}" +
                    if (failure.suppressedExceptions.isNotEmpty()) " 일부 기록 복구를 완료하지 못했습니다. 파일을 확인해 주세요." else "", failure)
            }
        } catch (failure: Exception) {
            if (initialized) {
                runCatching {
                    child.list().firstOrNull { it.name == PROJECT_CONFIG_FILE_NAME }?.let { file ->
                        val expected = configJson.encodeToString(ProjectConfig.serializer(), defaultProjectConfig())
                        check(file.readString() == expected) { "외부에서 변경한 프로젝트 설정은 제거하지 않았습니다." }
                        file.delete()
                    }
                    DEFAULT_PROJECT_FOLDERS.filter { template -> initialEntries.none { it.name == template.name } }.forEach { template ->
                        child.list().firstOrNull { it.name == template.name }?.let { if (it.list().isEmpty()) it.delete() }
                    }
                }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    /** 화면에서의 진입은 이 경로를 사용한다. 미설정 폴더는 사용자 확인 전까지 열지 않는다. */
    suspend fun requestOpenWorkspace(directory: PlatformFile, configure: Boolean = false) =
        workspaceOpenMutex.withLock {
            if (_workspaceOpenRequest.value != null) return@withLock
            when (workspaceSetup(directory)) {
                // 선택 화면이 인덱싱 화면으로 교체되어도 시작한 진입은 끝까지 완료한다.
                WorkspaceSetup.PROJECT -> activateProject(directory)
                WorkspaceSetup.GENERAL -> if (configure) {
                    _workspaceOpenRequest.value = WorkspaceOpenRequest(directory)
                } else openGeneralFolder(directory)
                WorkspaceSetup.NEEDS_CONFIRMATION -> {
                    _workspaceOpenRequest.value = WorkspaceOpenRequest(directory)
                }
            }
        }

    fun dismissWorkspaceOpenRequest() {
        if (_workspaceOpenRequest.value?.busy != true) _workspaceOpenRequest.value = null
    }

    suspend fun confirmWorkspaceOpen(kind: WorkspaceKind) = workspaceOpenMutex.withLock {
        val request = _workspaceOpenRequest.value ?: return@withLock
        _workspaceOpenRequest.value = request.copy(busy = true, errorMessage = null)
        try {
            if (kind == WorkspaceKind.PROJECT) {
                val (vault, directories) = currentVaultAndDirectories()
                val child = requireVaultChild(request.directory, directories)
                val config = vaultConfigMutex.withLock { readVaultConfigUnlocked(vault) }
                if (suspendedProject(child, config) != null) {
                    projectConfigMutex.withLock {
                        withContext(NonCancellable) { resumeWorkspaceProject(vault, child, config) }
                    }
                } else configureExistingProject(child)
                activateProject(child)
            } else {
                // 재검사해 확인창이 열린 사이 외부에서 설정한 Project를 일반 모드로 열지 않는다.
                check(workspaceSetup(request.directory) != WorkspaceSetup.PROJECT) {
                    "이 폴더에 프로젝트 설정이 생겼습니다. 취소한 뒤 다시 선택해 주세요."
                }
                val (vault, directories) = currentVaultAndDirectories()
                val child = requireVaultChild(request.directory, directories)
                updateVaultConfig(vault) { config ->
                    config.copy(generalFolders = config.generalFolders + child.name)
                }
                openGeneralFolder(request.directory)
            }
            _workspaceOpenRequest.value = null
        } catch (error: Exception) {
            _workspaceOpenRequest.value = request.copy(errorMessage = error.message ?: "폴더를 열지 못했습니다.")
            if (error is CancellationException) throw error
        }
    }

    private suspend fun openGeneralFolder(directory: PlatformFile, restored: Bookmarks? = null) {
        val folders = listFolders(directory)
        projectIndexer.reset()
        setPreferences((restored ?: getPreferences().copy(fileData = null, fileRelativePath = null)).copy(
            projectData = directory,
            workspaceKind = WorkspaceKind.GENERAL,
        ))
        _projectConfig.value = ProjectConfig(folders = folders.associate {
            it.key.relativePath to FolderConfig(type = FolderType.GENERAL)
        })
    }

    /**
     * 일반 폴더에서 해당 Vault의 마지막 Project로 돌아간다.
     * 기록이 없거나 Project가 사라졌다면 현재 Vault만 남겨 Project 선택 화면으로 이동한다.
     */
    suspend fun returnToLastProject() = workspaceOpenMutex.withLock {
        val (vault, directories) = currentVaultAndDirectories()
        val config = loadVaultConfig(vault, directories)
        val project = config.lastProject
            ?.let { name -> directories.firstOrNull { it.name == name } }
            ?.takeIf { suspendedProject(it, config) == null && it.name !in config.generalFolders && hasValidProjectConfig(it) }

        if (project != null) {
            activateProject(project)
            return@withLock
        }

        if (config.lastProject != null) {
            updateVaultConfig(vault) { it.copy(lastProject = null) }
        }
        projectIndexer.reset()
        _projectConfig.value = null
        setPreferences(Bookmarks(vaultData = vault))
    }

    /** 기존 내용은 재사용하고, 이번 시도에서 만든 빈 디렉터리와 설정만 실패 시 정리한다. */
    private suspend fun configureExistingProject(directory: PlatformFile) = withContext(Dispatchers.IO) {
        val entries = directory.list()
        val existingConfig = entries.find { it.name == PROJECT_CONFIG_FILE_NAME }
        if (existingConfig != null && readConfig(existingConfig) != null) return@withContext
        DEFAULT_PROJECT_FOLDERS.forEach { template ->
            val existing = entries.find { it.name.equals(template.name, ignoreCase = true) }
            check(existing == null || (existing.isDirectory() && existing.name == template.name)) {
                "${template.name}: 같은 이름의 파일 또는 다른 대소문자의 폴더가 있습니다."
            }
        }
        val created = mutableListOf<PlatformFile>()
        var configFile: PlatformFile? = null
        try {
            DEFAULT_PROJECT_FOLDERS.forEach { template ->
                if (entries.none { it.name == template.name }) {
                    created += createFolder(directory, template.name) ?: error("${template.name} 폴더를 만들지 못했습니다.")
                }
            }
            configFile = setConfig(directory, PROJECT_CONFIG_FILE_NAME)
                ?: error("프로젝트 설정을 저장하지 못했습니다.")
            persistConfig(configFile, defaultProjectConfig())
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching {
                    if (existingConfig == null) configFile?.delete() else existingConfig.writeString("")
                }
                created.asReversed().forEach { folder ->
                    runCatching { if (folder.list().isEmpty()) folder.delete() }
                }
            }
            throw error
        }
    }

    suspend fun createProject(name: String): PlatformFile? = withContext(Dispatchers.IO) {
        val vault = _bookmarks.value.vaultData ?: return@withContext null
        val projectName = name.trim()
        if (!isValidProjectFolderName(projectName)) return@withContext null
        if (vault.list().any { it.name.equals(projectName, ignoreCase = true) }) {
            return@withContext null
        }

        val project = createFolder(vault, projectName) ?: return@withContext null
        val initialized = runCatching { initializeNewProject(project) }.getOrDefault(false)
        if (!initialized) {
            rollbackNewProject(project)
            return@withContext null
        }
        project
    }

    /**
     * Vault 내부의 프로젝트 폴더 목록
     * @param vault 사용자 지정 또는 북마크에서 복원된 루트 디렉토리 경로
     * @return 프로젝트 폴더 목록
     */
    suspend fun listProject(vault: PlatformFile): List<PlatformFile> = withContext(Dispatchers.IO) {
        vault.list()
            .filter { it.isDirectory() && !it.name.startsWith(".") }
            .sortedBy { it.name }
    }

    /** Caller must hold the editor save fence before moving an active workspace. */
    suspend fun moveWorkspaceToTrash(vault: PlatformFile, directory: PlatformFile): WorkspaceTrashResult =
        workspaceOpenMutex.withLock {
            withContext(Dispatchers.IO) {
                requireSelectedVault(vault)
                val child = requireVaultChild(directory, listProject(vault))
                validateWorkspaceTrashChild(vault, child)
                workspaceTrashMutex.withLock {
                    val config = vaultConfigMutex.withLock { readVaultConfigUnlocked(vault) }
                    val suspended = suspendedProject(child, config)
                    val trash = vault.list().firstOrNull { it.name == WORKSPACE_TRASH_NAME }
                        ?: createFolderExclusive(vault, WORKSPACE_TRASH_NAME)
                        ?: error("Vault 휴지통을 만들지 못했습니다.")
                    validateWorkspaceTrashChild(vault, trash)
                    val id = Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                    val entry = createFolderExclusive(trash, id) ?: error("휴지통 항목을 만들지 못했습니다.")
                    check(entry.name == id) { "휴지통 항목 이름이 요청과 다릅니다." }
                    validateWorkspaceTrashChild(trash, entry)
                    val receiptFile = setConfig(entry, WORKSPACE_TRASH_RECEIPT) ?: error("휴지통 기록을 만들지 못했습니다.")
                    var receipt = WorkspaceTrashReceipt(entryId = id, originalName = child.name,
                        originalLocation = child.toString(), suspendedProjects = suspended?.let { mapOf(it.key to it.value) }.orEmpty())
                    val initialText = configJson.encodeToString(WorkspaceTrashReceipt.serializer(), receipt)
                    receiptFile.writeString(initialText)
                    check(receiptFile.readString() == initialText) { "휴지통 기록을 확인하지 못했습니다." }
                    withContext(NonCancellable) {
                        val moved = moveWorkspaceItemNative(vault, vault, child, entry)
                        receipt = receipt.copy(movedAtEpochMillis = workspaceTrashClock(), movedLocation = moved.toString())
                        // The runtime must stop referring to moved paths even if persistent cleanup fails.
                        if (sameLocation(_bookmarks.value.projectData, child)) {
                            _bookmarks.value = Bookmarks(vaultData = vault)
                            _projectConfig.value = null
                            projectIndexer.reset()
                        }
                        if (sameLocation(_workspaceOpenRequest.value?.directory, child)) _workspaceOpenRequest.value = null
                        val warning = runCatching {
                            writeWorkspaceTrashReceipt(receiptFile, receipt)
                            completeWorkspaceTrashCleanup(vault, entry, receiptFile, receipt)
                        }.exceptionOrNull()?.let { "폴더는 휴지통으로 이동했습니다. 기록 정리를 완료하지 못했습니다: ${it.message}" }
                        _workspaceTrashWarning.value = warning
                        WorkspaceTrashResult(moved, warning)
                    }
                }
            }
        }

    /** Move only a current-workspace Markdown document; callers own the pending-save fence. */
    suspend fun moveProjectFileToTrash(fileKey: FileKey): ProjectFileTrashResult = workspaceOpenMutex.withLock {
        withContext(Dispatchers.IO) {
            val selected = _bookmarks.value
            val vault = selected.vaultData ?: error("Vault를 다시 선택해 주세요.")
            val workspace = selected.projectData ?: error("작업 공간을 다시 선택해 주세요.")
            requireVaultChild(workspace, listProject(vault))
            check(fileKey.fileName.endsWith(".md", ignoreCase = true)) { "Markdown 문서만 휴지통으로 이동할 수 있습니다." }
            check(fileKey.folder == FolderKey.Base || !fileKey.folder.relativePath.startsWith('.')) { "관리용 숨김 폴더는 대상이 아닙니다." }
            validateWorkspaceTrashChild(vault, workspace)
            val parent = resolveFolder(workspace, fileKey.folder) ?: error("문서 폴더가 없습니다.")
            if (!sameLocation(parent, workspace)) validateWorkspaceTrashChild(workspace, parent)
            val source = resolveRelativeFile(workspace, fileKey.relativePath) ?: error("문서가 없거나 이동되었습니다.")
            validateWorkspaceTrashFile(parent, source)
            workspaceTrashMutex.withLock {
                val identity = ensureFolderIdentity(workspace)
                checkUniqueWorkspaceIdentity(vault, identity)
                val removedIds = projectConfigMutex.withLock {
                    if (selected.workspaceKind == WorkspaceKind.GENERAL) emptyMap() else {
                        val configFile = workspace.list().singleOrNull { it.name == PROJECT_CONFIG_FILE_NAME && !it.isDirectory() }
                            ?: error("프로젝트 설정을 읽지 못했습니다.")
                        val config = readConfig(configFile) ?: error("프로젝트 설정이 비어 있습니다.")
                        config.fileIds.filterValues { it == fileKey.relativePath }
                    }
                }
                val trash = vault.list().firstOrNull { it.name == WORKSPACE_TRASH_NAME }
                    ?: createFolderExclusive(vault, WORKSPACE_TRASH_NAME) ?: error("Vault 휴지통을 만들지 못했습니다.")
                validateWorkspaceTrashChild(vault, trash)
                val id = Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                val entry = createFolderExclusive(trash, id) ?: error("휴지통 항목을 만들지 못했습니다.")
                check(entry.name == id) { "휴지통 항목 이름이 요청과 다릅니다." }
                validateWorkspaceTrashChild(trash, entry)
                val receiptFile = setConfig(entry, WORKSPACE_TRASH_RECEIPT) ?: error("휴지통 기록을 만들지 못했습니다.")
                var receipt = WorkspaceTrashReceipt(entryId = id, originalName = source.name,
                    originalLocation = source.toString(), kind = WorkspaceTrashKind.FILE,
                    workspaceIdentity = identity, workspaceLocation = workspace.toString(),
                    originalRelativePath = fileKey.relativePath, removedFileIds = removedIds,
                    projectConfigRecoveryFile = WORKSPACE_TRASH_CONFIG_RECOVERY.takeIf { removedIds.isNotEmpty() })
                writeWorkspaceTrashReceipt(receiptFile, receipt)
                withContext(NonCancellable) {
                    val moved = moveWorkspaceItemNative(vault, parent, source, entry)
                    receipt = receipt.copy(movedAtEpochMillis = workspaceTrashClock(), movedLocation = moved.toString())
                    if (sameLocation(_bookmarks.value.projectData, workspace) && _bookmarks.value.fileRelativePath == fileKey.relativePath) {
                        _bookmarks.value = _bookmarks.value.copy(fileData = null, fileRelativePath = null)
                    }
                    // Any previously completed index describes the old tree, including after a cleanup failure.
                    projectIndexer.reset()
                    val warning = runCatching {
                        writeWorkspaceTrashReceipt(receiptFile, receipt)
                        completeProjectFileTrashCleanup(vault, entry, receiptFile, receipt)
                    }.exceptionOrNull()?.let { "문서는 휴지통으로 이동했습니다. 기록 정리를 완료하지 못했습니다: ${it.message}" }
                    _workspaceTrashWarning.value = warning
                    ProjectFileTrashResult(moved, warning)
                }
            }
        }
    }

    private suspend fun completeProjectFileTrashCleanup(vault: PlatformFile, entry: PlatformFile, file: PlatformFile, receipt: WorkspaceTrashReceipt) {
        val relativePath = checkNotNull(receipt.originalRelativePath)
        val candidates = listProject(vault)
        val matches = candidates.filter { directory ->
            directory.list().firstOrNull { it.name == FOLDER_ID_FILE_NAME }?.let { marker ->
                runCatching { folderIdentity(marker) == receipt.workspaceIdentity }.getOrDefault(false)
            } == true
        }
        check(matches.size <= 1) { "작업 공간 식별자가 중복되어 문서 기록을 정리하지 않았습니다." }
        val workspace = matches.singleOrNull()
        if (workspace == null) {
            check(candidates.none { it.toString() == receipt.workspaceLocation }) { "작업 공간이 교체되어 문서 기록을 정리하지 않았습니다." }
        } else {
            validateWorkspaceTrashChild(vault, workspace)
            check(resolveRelativeFile(workspace, relativePath) == null) { "같은 경로에 새 문서가 있어 이전 문서 기록을 정리하지 않았습니다." }
            if (receipt.removedFileIds.isNotEmpty()) projectConfigMutex.withLock {
                val configFile = workspace.list().singleOrNull { it.name == PROJECT_CONFIG_FILE_NAME && !it.isDirectory() }
                    ?: error("프로젝트 설정을 읽지 못했습니다.")
                var originalText = configFile.readString()
                var recovery = readWorkspaceTrashConfigRecovery(entry, receipt)
                if (recovery != null && originalText != recovery.originalText && originalText != recovery.updatedText) {
                    check(recovery.updatedText.startsWith(originalText) || recovery.originalText.startsWith(originalText)) {
                        "프로젝트 설정에 다른 변경이 있어 덮어쓰지 않았습니다. 복구 사본: $entry/$WORKSPACE_TRASH_CONFIG_RECOVERY"
                    }
                    // Resume a recognizable interrupted write from the immutable copy before parsing the config.
                    check(configFile.readString() == originalText) { "복구 직전 프로젝트 설정이 외부에서 변경되었습니다." }
                    workspaceTransitionWriter(configFile, recovery.originalText)
                    check(configFile.readString() == recovery.originalText) { "프로젝트 설정 복구가 완료되지 않았습니다. 복구 사본: $entry/$WORKSPACE_TRASH_CONFIG_RECOVERY" }
                    originalText = recovery.originalText
                }
                val current = configJson.decodeFromString(ProjectConfig.serializer(), originalText)
                val updated = current.copy(fileIds = current.fileIds.filterNot { (id, path) -> receipt.removedFileIds[id] == path })
                val updatedText = configJson.encodeToString(ProjectConfig.serializer(), updated)
                if (recovery == null) {
                    recovery = WorkspaceTrashConfigRecovery(entryId = receipt.entryId,
                        workspaceIdentity = checkNotNull(receipt.workspaceIdentity), originalText = originalText, updatedText = updatedText)
                    val recoveryFile = setConfig(entry, WORKSPACE_TRASH_CONFIG_RECOVERY) ?: error("프로젝트 설정 복구 사본을 만들지 못했습니다.")
                    validateWorkspaceTrashFile(entry, recoveryFile)
                    val text = configJson.encodeToString(WorkspaceTrashConfigRecovery.serializer(), recovery)
                    check(recoveryFile.readString().isEmpty()) { "복구 사본 경로에 다른 내용이 있어 덮어쓰지 않았습니다." }
                    recoveryFile.writeString(text)
                    check(recoveryFile.readString() == text) { "프로젝트 설정 복구 사본을 확인하지 못해 설정을 변경하지 않았습니다." }
                }
                if (updated != current) {
                    check(originalText == recovery.originalText && updatedText == recovery.updatedText) { "프로젝트 설정이 복구 사본과 달라 자동 정리를 중단했습니다." }
                    check(configFile.readString() == originalText) { "프로젝트 설정이 외부에서 변경되었습니다." }
                    try {
                        workspaceTransitionWriter(configFile, updatedText)
                        check(configFile.readString() == updatedText) { "프로젝트 설정 기록이 완전하지 않습니다." }
                    } catch (failure: Exception) {
                        val observed = runCatching { configFile.readString() }.getOrNull()
                        if (observed != null && updatedText.startsWith(observed)) runCatching {
                            check(configFile.readString() == observed)
                            workspaceTransitionWriter(configFile, originalText)
                            check(configFile.readString() == originalText)
                        }.exceptionOrNull()?.let(failure::addSuppressed)
                        throw IllegalStateException("프로젝트 설정 기록/복구를 완료하지 못했습니다. 원문 복구 사본을 보존했습니다: $entry/$WORKSPACE_TRASH_CONFIG_RECOVERY", failure)
                    }
                }
                if (sameLocation(_bookmarks.value.projectData, workspace) && _bookmarks.value.workspaceKind == WorkspaceKind.PROJECT) {
                    _projectConfig.value = updated
                }
            }
        }
        dataStore.edit { pref ->
            val project = pref[BOOKMARK_PROJECT]?.let { runCatching { PlatformFile.fromBookmarkData(it).toString() }.getOrNull() }
            if ((project == receipt.workspaceLocation || project == workspace?.toString()) && pref[BOOKMARK_FILE] == relativePath) {
                pref.remove(BOOKMARK_FILE)
            }
        }
        validateWorkspaceTrashFile(entry, entry.list().single { it.name == receipt.originalName })
        // If the whole workspace has since disappeared, no config was written and no recovery copy is needed.
        val recoveryReference = receipt.projectConfigRecoveryFile.takeIf { reference -> entry.list().any { it.name == reference } }
        writeWorkspaceTrashReceipt(file, receipt.copy(cleanupComplete = true, projectConfigRecoveryFile = recoveryReference))
    }

    private suspend fun readWorkspaceTrashConfigRecovery(entry: PlatformFile, receipt: WorkspaceTrashReceipt): WorkspaceTrashConfigRecovery? {
        val recoveryFile = entry.list().singleOrNull { it.name == WORKSPACE_TRASH_CONFIG_RECOVERY } ?: return null
        check(receipt.kind == WorkspaceTrashKind.FILE && receipt.projectConfigRecoveryFile == WORKSPACE_TRASH_CONFIG_RECOVERY)
        validateWorkspaceTrashFile(entry, recoveryFile)
        val recovery = configJson.decodeFromString(WorkspaceTrashConfigRecovery.serializer(), recoveryFile.readString())
        check(recovery.format == 1 && recovery.entryId == receipt.entryId && recovery.workspaceIdentity == receipt.workspaceIdentity) {
            "프로젝트 설정 복구 사본의 식별자가 다릅니다."
        }
        val original = configJson.decodeFromString(ProjectConfig.serializer(), recovery.originalText)
        val expected = original.copy(fileIds = original.fileIds.filterNot { (id, path) -> receipt.removedFileIds[id] == path })
        check(configJson.encodeToString(ProjectConfig.serializer(), expected) == recovery.updatedText) { "프로젝트 설정 복구 사본의 변경 내용이 다릅니다." }
        return recovery
    }

    private suspend fun completeWorkspaceTrashCleanup(vault: PlatformFile, entry: PlatformFile, file: PlatformFile, receipt: WorkspaceTrashReceipt) {
        check(vault.list().none { it.name == receipt.originalName }) {
            "원래 위치에 같은 이름의 항목이 있어 이전 작업 공간 기록을 자동으로 정리하지 않았습니다."
        }
        updateWorkspaceVaultConfig(vault) { config ->
            config.copy(generalFolders = config.generalFolders - receipt.originalName,
                lastProject = config.lastProject?.takeUnless { it == receipt.originalName },
                suspendedProjects = config.suspendedProjects.filterNot { (key, value) -> receipt.suspendedProjects[key] == value })
        }
        dataStore.edit { pref ->
            val project = pref[BOOKMARK_PROJECT]?.let { runCatching { PlatformFile.fromBookmarkData(it).toString() }.getOrNull() }
            if (project == receipt.originalLocation) {
                pref.remove(BOOKMARK_PROJECT)
                pref.remove(BOOKMARK_FILE)
                pref.remove(WORKSPACE_KIND)
            }
            val legacy = pref[GENERAL_FOLDERS].orEmpty() - receipt.originalLocation
            if (legacy.isEmpty()) pref.remove(GENERAL_FOLDERS) else pref[GENERAL_FOLDERS] = legacy
        }
        validateWorkspaceTrashChild(entry, entry.list().single { it.name == receipt.originalName })
        writeWorkspaceTrashReceipt(file, receipt.copy(cleanupComplete = true))
    }

    private suspend fun writeWorkspaceTrashReceipt(file: PlatformFile, receipt: WorkspaceTrashReceipt) {
        val text = configJson.encodeToString(WorkspaceTrashReceipt.serializer(), receipt)
        file.writeString(text)
        check(file.readString() == text) { "휴지통 기록이 완전하게 저장되지 않았습니다." }
    }

    /** Best effort on Vault listing. Invalid/incomplete entries are preserved, never guessed. */
    private suspend fun maintainWorkspaceTrash(vault: PlatformFile) = workspaceTrashMutex.withLock {
        val warnings = mutableListOf<String>()
        try {
            val trash = vault.list().firstOrNull { it.name == WORKSPACE_TRASH_NAME } ?: return@withLock
            validateWorkspaceTrashChild(vault, trash)
            for (entry in trash.list()) {
                if (!workspaceTrashIdPattern.matches(entry.name) || !entry.isDirectory()) continue
                try {
                    validateWorkspaceTrashChild(trash, entry)
                    val items = entry.list()
                    val file = items.singleOrNull { it.name == WORKSPACE_TRASH_RECEIPT && !it.isDirectory() } ?: continue
                    var receipt = configJson.decodeFromString(WorkspaceTrashReceipt.serializer(), file.readString())
                    if (receipt.format != 1 || receipt.entryId != entry.name || !validVaultChildName(receipt.originalName)) continue
                    if (receipt.kind == WorkspaceTrashKind.FILE && (
                        receipt.workspaceIdentity?.let(workspaceTrashIdPattern::matches) != true || receipt.workspaceLocation == null ||
                            receipt.originalRelativePath?.let { path -> runCatching { FileKey.of(path).fileName == receipt.originalName }.getOrDefault(false) } != true ||
                            !receipt.originalName.endsWith(".md", ignoreCase = true) ||
                            (receipt.projectConfigRecoveryFile != WORKSPACE_TRASH_CONFIG_RECOVERY.takeIf { receipt.removedFileIds.isNotEmpty() } &&
                                !(receipt.cleanupComplete && receipt.projectConfigRecoveryFile == null)) ||
                            receipt.removedFileIds.values.any { it != receipt.originalRelativePath }
                        )) continue
                    val movedAt = receipt.movedAtEpochMillis ?: continue
                    val recoveryFile = items.singleOrNull { it.name == WORKSPACE_TRASH_CONFIG_RECOVERY }
                    if (recoveryFile != null) readWorkspaceTrashConfigRecovery(entry, receipt)
                    val payloadItems = items.filterNot { it.name == WORKSPACE_TRASH_RECEIPT || it.name == WORKSPACE_TRASH_CONFIG_RECOVERY }
                    val hasPayload = payloadItems.size == 1 && payloadItems.any {
                        it.name == receipt.originalName && it.isDirectory() == (receipt.kind == WorkspaceTrashKind.WORKSPACE) &&
                            it.toString() == receipt.movedLocation
                    }
                    val purgeInterruptedAfterPayload = receipt.cleanupComplete && receipt.movedLocation != null && payloadItems.isEmpty()
                    if (movedAt < 0 || (!hasPayload && !purgeInterruptedAfterPayload)) continue
                    if (hasPayload && receipt.cleanupComplete && receipt.projectConfigRecoveryFile != null && recoveryFile == null) continue
                    if (!receipt.cleanupComplete) {
                        if (receipt.kind == WorkspaceTrashKind.FILE) completeProjectFileTrashCleanup(vault, entry, file, receipt)
                        else completeWorkspaceTrashCleanup(vault, entry, file, receipt)
                        receipt = receipt.copy(cleanupComplete = true)
                    }
                    val now = workspaceTrashClock()
                    if (receipt.cleanupComplete && now >= movedAt && now - movedAt >= WORKSPACE_TRASH_RETENTION_MS) {
                        workspaceTrashPurger(trash, entry)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { warnings += "${entry.name}: ${error.message}" }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { warnings += error.message.orEmpty() }
        _workspaceTrashWarning.value = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** 하단 Project 후보와 상단 일반 폴더 위치를 한 번의 Vault 조회로 나눈다. */
    suspend fun listWorkspaceDirectories(vault: PlatformFile): WorkspaceDirectoryLists =
        withContext(Dispatchers.IO) {
            maintainWorkspaceTrash(vault)
            val directories = listProject(vault)
            val config = loadVaultConfig(vault, directories)
            val projectChoices = mutableListOf<PlatformFile>()
            val generalFolders = mutableListOf<PlatformFile>()
            directories.forEach { directory ->
                // `.machum.json`이 손상됐더라도 일반 폴더로 우회해서 열지는 않는다.
                val hasProjectMarker = directory.list().any { it.name == PROJECT_CONFIG_FILE_NAME }
                val isGeneral = suspendedProject(directory, config) != null ||
                    (!hasProjectMarker && directory.name in config.generalFolders)
                if (isGeneral) generalFolders += directory else projectChoices += directory
            }
            WorkspaceDirectoryLists(projectChoices, generalFolders)
        }

    suspend fun workspaceExists(directory: PlatformFile): Boolean = withContext(Dispatchers.IO) {
        runCatching { directory.exists() }.getOrDefault(false)
    }

    /**
     * 현재 탐색 루트가 외부에서 삭제된 경우에만 저장된 선택과 런타임 설정을 비운다.
     * 삭제된 파일에 pending 내용을 쓰면 경로를 되살릴 수 있으므로 호출자가 저장 예약을 먼저 취소한다.
     */
    suspend fun clearMissingWorkspace(expected: PlatformFile): Boolean = withContext(Dispatchers.IO) {
        if (workspaceExists(expected)) return@withContext false
        val current = _bookmarks.value
        if (!sameLocation(current.projectData, expected)) return@withContext false

        val vault = current.vaultData
        if (vault == null || !workspaceExists(vault)) {
            dataStore.edit { pref ->
                pref.remove(BOOKMARK_VAULT)
                pref.remove(BOOKMARK_PROJECT)
                pref.remove(BOOKMARK_FILE)
                pref.remove(WORKSPACE_KIND)
            }
            _bookmarks.value = Bookmarks()
        } else {
            dataStore.edit { pref ->
                pref.remove(BOOKMARK_PROJECT)
                pref.remove(BOOKMARK_FILE)
                pref.remove(WORKSPACE_KIND)
            }
            _bookmarks.value = Bookmarks(vaultData = vault)
            runCatching {
                updateVaultConfig(vault) { config ->
                    config.copy(
                        generalFolders = config.generalFolders - expected.name,
                        lastProject = config.lastProject?.takeUnless { it == expected.name },
                    )
                }
            }
        }
        _projectConfig.value = null
        projectIndexer.reset()
        _workspaceOpenRequest.value = null
        true
    }

    /** 프로젝트 base와 바로 아래의 비숨김 폴더를 반환한다. 중첩 폴더는 지원 범위 밖이다. */
    suspend fun listFolders(project: PlatformFile): List<ProjectFolder> = withContext(Dispatchers.IO) {
        listOf(ProjectFolder(FolderKey.Base, project)) + project.list()
            .filter { it.isDirectory() && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .map { folder -> ProjectFolder(FolderKey.of(folder.name), folder) }
    }

    /** 지정한 프로젝트 폴더의 직속 Markdown 파일을 상대 경로 정체성과 함께 반환한다. */
    suspend fun listProjectFiles(folder: ProjectFolder): List<ProjectFile> = withContext(Dispatchers.IO) {
        folder.platformFile.list()
            .filter { !it.isDirectory() && it.name.endsWith(".md", ignoreCase = true) }
            .sortedBy { it.name }
            .map { file -> ProjectFile(folder.key.file(file.name), file) }
    }

    /** 지정 폴더에 파일을 만들고, 관리 Project인 경우 생성 시점의 ID·Project 태그를 기록한다. */
    suspend fun createProjectFile(
        folder: ProjectFolder,
        name: String,
        initialNote: NoteFile? = null,
        initialSource: String? = null,
    ): ProjectFile? = workspaceOpenMutex.withLock {
        val workspace = _bookmarks.value
        val project = workspace.projectData ?: return@withLock null
        if (!isValidProjectFileTitle(name)) return@withLock null
        withContext(Dispatchers.IO) {
            if (!sameLocation(resolveFolder(project, folder.key), folder.platformFile)) return@withContext null
            val initial = initialNote ?: NoteFile.parse("")
            val created = if (workspace.workspaceKind == WorkspaceKind.PROJECT) {
                check(initialSource == null) { "source 구분에서 파일 만들기는 General 작업 공간에서만 가능합니다." }
                createFile(folder.platformFile, name, initial.withProjectMetadata(normalizeTag(project.name)).inject())
            } else generalSources.createFile(project, folder.platformFile, name, initial.inject(), initialSource)
            created?.let { file ->
                ProjectFile(folder.key.file(file.name), file)
            }
        }
    }

    /** Retry only this request's incomplete write; never allocate another name or overwrite external edits. */
    suspend fun retryProjectFileCreation(
        folder: ProjectFolder,
        failure: FileCreationIncompleteException,
    ): ProjectFile = workspaceOpenMutex.withLock {
        withContext(Dispatchers.IO) {
            val project = _bookmarks.value.projectData ?: error("작업 공간을 다시 선택해 주세요.")
            check(sameLocation(resolveFolder(project, folder.key), folder.platformFile)) {
                "생성 위치가 변경되었습니다. 파일을 확인해 주세요: ${failure.file}"
            }
            check(folder.platformFile.list().any { sameLocation(it, failure.file) && !it.isDirectory() }) {
                "생성한 파일을 찾을 수 없습니다: ${failure.file}"
            }
            val observed = failure.observedContent
                ?: error("생성 파일의 상태를 확인할 수 없습니다. 직접 확인해 주세요: ${failure.file}")
            check(failure.file.readString() == observed) {
                "생성 파일이 외부에서 변경되었습니다. 덮어쓰지 않았습니다: ${failure.file}"
            }
            try {
                failure.file.writeString(failure.expectedContent)
            } catch (error: Exception) {
                throw incompleteFileCreation(failure.file, failure.expectedContent, error)
            }
            ProjectFile(folder.key.file(failure.file.name), failure.file)
        }
    }

    /** Default 폴더의 관리 대상(숫자 접두사) 파일만 전달된 순서대로 안전하게 재번호한다. */
    suspend fun applyDefaultOrder(
        folder: ProjectFolder,
        orderedFileKeys: List<FileKey>,
    ): List<DefaultOrderUpdate>? = withContext(Dispatchers.IO) {
        if (_bookmarks.value.workspaceKind == WorkspaceKind.GENERAL) return@withContext null
        if (orderedFileKeys.toSet().size != orderedFileKeys.size) return@withContext null

        val files = listProjectFiles(folder)
        val filesByKey = files.associateBy(ProjectFile::key)
        val managedFiles = files.filter { it.numberedPrefix() != null }
        val managedKeys = managedFiles.mapTo(mutableSetOf(), ProjectFile::key)
        if (orderedFileKeys.toSet() != managedKeys) return@withContext null
        if (orderedFileKeys.any { it.folder != folder.key }) return@withContext null
        if (orderedFileKeys.isEmpty()) return@withContext emptyList()

        val untouchedNames = files
            .filterNot { it.key in managedKeys }
            .mapTo(mutableSetOf()) { it.platformFile.name.lowercase() }
        val startAt = if (folder.key == FolderKey.Base) 0 else 1
        val works = orderedFileKeys.mapIndexed { index, fileKey ->
            val source = filesByKey[fileKey] ?: return@withContext null
            val title = source.defaultOrderTitle().takeIf { it.isNotBlank() }
                ?: return@withContext null
            val finalBaseName = "${startAt + index}. $title"
            if ("$finalBaseName.md".lowercase() in untouchedNames) return@withContext null
            MarkdownOrderWork(
                oldKey = source.key,
                originalBaseName = source.platformFile.nameWithoutExtension,
                originalMarkdown = null,
                updatedNoteFile = null,
                finalBaseName = finalBaseName,
                temporaryBaseName = ".machum-order-default-$index",
                currentFile = source.platformFile,
            )
        }
        if (works.map { it.finalBaseName.lowercase() }.toSet().size != works.size) {
            return@withContext null
        }
        if (!applyMarkdownOrderTransaction(folder, works)) return@withContext null

        works.map { work ->
            DefaultOrderUpdate(
                oldKey = work.oldKey,
                projectFile = ProjectFile(folder.key.file(work.currentFile.name), work.currentFile),
            )
        }
    }

    /** PLOT 순서 초안을 frontmatter와 정확한 파일명에 일괄 반영한다. */
    suspend fun applyPlotOrder(
        folder: ProjectFolder,
        assignments: List<PlotOrderAssignment>,
    ): List<PlotOrderUpdate>? = withContext(Dispatchers.IO) {
        if (_bookmarks.value.workspaceKind == WorkspaceKind.GENERAL) return@withContext null
        if (assignments.isEmpty()) return@withContext emptyList()
        if (assignments.map { it.fileKey }.toSet().size != assignments.size) return@withContext null
        if (assignments.any {
                it.fileKey.folder != folder.key || it.order < PlotStage.FIRST_ORDER
            }
        ) return@withContext null
        if (assignments.map { it.stage to it.order }.toSet().size != assignments.size) {
            return@withContext null
        }

        val files = listProjectFiles(folder)
        val filesByKey = files.associateBy(ProjectFile::key)
        val assignmentKeys = assignments.mapTo(mutableSetOf()) { it.fileKey }
        val originalMarkdown = files.associate { projectFile ->
            projectFile.key to projectFile.platformFile.readString()
        }
        val originalNotes = originalMarkdown.mapValues { (_, raw) -> NoteFile.parse(raw) }
        val classifiedKeys = originalNotes
            .filterValues { noteFile -> noteFile.plotStage != null }
            .keys
        // 미분류 파일은 drag로 새 단계에 편입할 수 있지만, 이미 Plot 단계가 있는 파일을
        // 누락한 부분 목록은 기존 논리 순번과 충돌할 수 있으므로 허용하지 않는다.
        if (!assignmentKeys.containsAll(classifiedKeys)) return@withContext null
        val untouchedNames = filesByKey.values
            .filterNot { it.key in assignmentKeys }
            .mapTo(mutableSetOf()) { it.platformFile.name.lowercase() }

        val works = assignments.mapIndexed { index, assignment ->
            val source = filesByKey[assignment.fileKey] ?: return@withContext null
            val noteFile = originalNotes.getValue(source.key)
            val title = source.plotTitle()
            val finalBaseName = assignment.stage.fileName(assignment.order, title)
            val finalFileName = "$finalBaseName.md"
            if (finalFileName.lowercase() in untouchedNames) return@withContext null
            MarkdownOrderWork(
                oldKey = source.key,
                originalBaseName = source.platformFile.nameWithoutExtension,
                originalMarkdown = originalMarkdown.getValue(source.key),
                updatedNoteFile = noteFile.withPlotStage(assignment.stage),
                finalBaseName = finalBaseName,
                temporaryBaseName = ".machum-order-plot-$index",
                currentFile = source.platformFile,
            )
        }
        if (works.map { it.finalBaseName.lowercase() }.toSet().size != works.size) {
            return@withContext null
        }

        if (!applyMarkdownOrderTransaction(folder, works)) return@withContext null

        works.map { work ->
            PlotOrderUpdate(
                oldKey = work.oldKey,
                projectFile = ProjectFile(folder.key.file(work.currentFile.name), work.currentFile),
                noteFile = work.updatedNoteFile ?: return@withContext null,
            )
        }
    }

    /** Create a unique direct child and persist its explicit type before reporting success. */
    suspend fun createProjectFolder(
        name: String,
        folderConfig: FolderConfig,
    ): ProjectFolder? = createProjectFolderWithConfigWriter(name, folderConfig, ::persistProjectConfig)

    internal suspend fun createProjectFolderWithConfigWriter(
        name: String,
        folderConfig: FolderConfig,
        persist: suspend (PlatformFile, ProjectConfig) -> ProjectConfig?,
    ): ProjectFolder? = workspaceOpenMutex.withLock {
        withContext(Dispatchers.IO) {
            val project = _bookmarks.value.projectData ?: return@withContext null
            val requestedName = name.trim()
            if (!isValidProjectFolderName(requestedName)) return@withContext null
            projectConfigMutex.withLock config@{
                val previousConfig = _projectConfig.value ?: return@config null
                val siblings = project.list()
                val names = siblings.map { it.name.lowercase() }.toSet()
                var directoryName = requestedName
                var suffix = 1
                while (directoryName.lowercase() in names) directoryName = "${requestedName}_${suffix++}"
                val configFile = if (_bookmarks.value.workspaceKind == WorkspaceKind.PROJECT) {
                    siblings.firstOrNull { it.name == PROJECT_CONFIG_FILE_NAME }
                } else null
                val originalConfig = configFile?.readString()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val directory = createFolderExclusive(project, directoryName)
                        ?: error("폴더를 만들지 못했습니다. 목록을 새로고침해 주세요.")
                    check(directory.name == directoryName && directory.isDirectory() &&
                        project.list().any { sameLocation(it, directory) && it.name == directoryName }) {
                        "요청한 이름과 다른 폴더가 생성되었습니다: $directory. 목록에서 확인해 주세요."
                    }
                    val key = FolderKey.of(directory.name)
                    try {
                        val updated = previousConfig.copy(folders = previousConfig.folders +
                            (key.relativePath to folderConfig))
                        checkNotNull(persist(project, updated)) { "폴더 설정을 기록하지 못했습니다." }
                    } catch (error: Exception) {
                        val restored = runCatching {
                            if (originalConfig != null) configFile.writeString(originalConfig)
                            _projectConfig.value = previousConfig
                        }.isSuccess
                        val removed = restored && runCatching { deleteEmptyFolderExclusive(directory) }.getOrDefault(false)
                        throw IllegalStateException(
                            (if (removed) "폴더 설정에 실패하여 새 빈 폴더를 정리했습니다."
                            else "폴더 설정에 실패했습니다. 생성된 폴더를 확인해 주세요: $directory") + " ${error.message}", error,
                        )
                    }
                    ProjectFolder(key, directory)
                }
            }
        }
    }

    /** 직속 Project 폴더와 이에 연결된 설정·ID·선택 경로를 하나의 작업으로 변경한다. */
    suspend fun renameProjectFolder(
        folder: ProjectFolder,
        newName: String,
        folderConfig: FolderConfig,
    ): FolderRenameUpdate? = projectConfigMutex.withLock {
        val project = _bookmarks.value.projectData ?: return@withLock null
        val previousKey = folder.key
        if (previousKey == FolderKey.Base) return@withLock null
        val directoryName = newName.trim()
        if (!isValidProjectFolderName(directoryName)) return@withLock null
        if (directoryName.equals(previousKey.relativePath, ignoreCase = true)) return@withLock null
        val previousConfig = _projectConfig.value ?: return@withLock null
        val previousBookmarks = _bookmarks.value
        val persistsConfig = previousBookmarks.workspaceKind == WorkspaceKind.PROJECT
        var originalConfig: Pair<PlatformFile, String>? = null
        var renamedDirectory: PlatformFile? = null
        var configWriteAttempted = false
        var bookmarkWriteAttempted = false

        try {
            // catch는 IO context 바깥에 둔다. IO 완료 후 caller로 복귀할 때의 취소도 rollback 대상이다.
            val result = withContext(Dispatchers.IO) {
                if (!sameLocation(resolveFolder(project, previousKey), folder.platformFile)) return@withContext null
                val children = project.list()
                if (children.any { it.name.equals(directoryName, ignoreCase = true) }) return@withContext null
                if (persistsConfig) {
                    originalConfig = children.firstOrNull { it.name == PROJECT_CONFIG_FILE_NAME }
                        ?.let { it to it.readString() }
                }
                val previousFileNames = listProjectFiles(folder).mapTo(mutableSetOf()) { it.key.fileName }

                // 실제 이름 변경의 결과를 반드시 확보한 뒤 취소를 검사해야 이전 경로로 되돌릴 수 있다.
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    renamedDirectory = renameDirectoryExact(project, folder.platformFile, directoryName)
                }
                currentCoroutineContext().ensureActive()
                val renamed = renamedDirectory ?: return@withContext null
                val updatedKey = FolderKey.of(renamed.name)
                val updatedFolder = ProjectFolder(updatedKey, renamed)
                // SAF도 새 디렉터리에서 실제 handle을 다시 얻는다. 조회 실패 역시 rename rollback 대상이다.
                val updatedFiles = listProjectFiles(updatedFolder)
                check(updatedFiles.mapTo(mutableSetOf()) { it.key.fileName } == previousFileNames) {
                    "folder contents changed during rename"
                }
                val updatedConfig = previousConfig.renameFolder(
                    previousPath = previousKey.relativePath,
                    updatedPath = updatedKey.relativePath,
                    updatedFolderConfig = folderConfig,
                )

                configWriteAttempted = true
                val persisted = persistProjectConfig(project, updatedConfig) ?: error("config save failed")
                val previousSelectedPath = previousBookmarks.fileRelativePath
                val updatedSelectedPath = previousSelectedPath?.replaceFolderPrefix(previousKey, updatedKey)
                val updatedSelectedFile = if (updatedSelectedPath != previousSelectedPath) {
                    resolveRelativeFile(project, updatedSelectedPath ?: error("selected path missing"))
                        ?: error("renamed selected file missing")
                } else {
                    previousBookmarks.fileData
                }
                if (updatedSelectedPath != previousSelectedPath) {
                    bookmarkWriteAttempted = true
                    setPreferences(
                        previousBookmarks.copy(
                            fileData = updatedSelectedFile,
                            fileRelativePath = updatedSelectedPath,
                        )
                    )
                }
                currentCoroutineContext().ensureActive()
                FolderRenameUpdate(
                    previousKey = previousKey,
                    projectFolder = updatedFolder,
                    filesByPreviousKey = updatedFiles.associateBy { previousKey.file(it.key.fileName) },
                    projectConfig = persisted,
                    selectedFileKey = updatedSelectedPath
                        ?.takeIf { it != previousSelectedPath }
                        ?.let(FileKey::of),
                )
            }
            currentCoroutineContext().ensureActive()
            result
        } catch (error: Exception) {
            val renamed = renamedDirectory
            if (renamed != null) {
                // 복구 결과를 caller의 취소가 가리지 않도록 먼저 같은 dispatcher에서 NonCancellable로 진입한다.
                val rollbackFailures = withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        buildList {
                            runCatching {
                                checkNotNull(renameDirectoryExact(project, renamed, previousKey.relativePath)) {
                                    "original directory restore failed"
                                }
                            }.exceptionOrNull()?.let(::add)
                            if (configWriteAttempted) {
                                runCatching {
                                    if (persistsConfig) {
                                        val snapshot = originalConfig
                                        if (snapshot != null) {
                                            snapshot.first.writeString(snapshot.second)
                                        } else {
                                            // 변경 전 없었던 설정만 제거한다. 폴더나 다른 사용자 파일은 삭제하지 않는다.
                                            project.list().firstOrNull { it.name == PROJECT_CONFIG_FILE_NAME }?.let {
                                                check(!it.isDirectory()) { "config path is no longer a file" }
                                                it.delete()
                                            }
                                        }
                                    }
                                    _projectConfig.value = previousConfig
                                }.exceptionOrNull()?.let(::add)
                            }
                            if (bookmarkWriteAttempted) {
                                runCatching { setPreferences(previousBookmarks) }.exceptionOrNull()?.let(::add)
                            }
                        }
                    }
                }
                if (rollbackFailures.isNotEmpty()) {
                    throw FolderRenameRollbackException(previousKey.relativePath, directoryName, error, rollbackFailures)
                }
            }
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /** 삭제 확인 UI에 필요한 직속 Markdown 파일과 앱에서 삭제할 수 없는 항목을 점검한다. */
    suspend fun inspectProjectFolderDeletion(
        folderKey: FolderKey,
    ): ProjectFolderDeletionPreview? = withContext(Dispatchers.IO) {
        val project = _bookmarks.value.projectData ?: return@withContext null
        inspectProjectFolderDeletion(project, folderKey)
    }

    /** 점검 결과가 안전한 경우에만 실제 폴더와 설정·ID·선택 bookmark를 함께 제거한다. */
    suspend fun deleteProjectFolder(
        folderKey: FolderKey,
    ): FolderDeletionUpdate? = withContext(Dispatchers.IO) {
        val project = _bookmarks.value.projectData ?: return@withContext null
        if (folderKey == FolderKey.Base) return@withContext null

        projectConfigMutex.withLock {
            val preview = inspectProjectFolderDeletion(project, folderKey) ?: return@withLock null
            if (!preview.canDelete) return@withLock null
            val previousConfig = _projectConfig.value ?: return@withLock null
            val previousBookmarks = _bookmarks.value
            val updatedConfig = previousConfig.removeFolder(folderKey.relativePath)
            val selectedInsideFolder = previousBookmarks.fileRelativePath?.startsWith("${folderKey.relativePath}/") == true

            try {
                persistProjectConfig(project, updatedConfig) ?: error("config save failed")
                if (selectedInsideFolder) {
                    setPreferences(
                        previousBookmarks.copy(
                            fileData = null,
                            fileRelativePath = null,
                        )
                    )
                }
                if (!deleteDirectoryExact(preview.folder.platformFile)) {
                    error("directory delete failed")
                }
                FolderDeletionUpdate(
                    folderKey = folderKey,
                    deletedFileKeys = preview.markdownFiles.map(ProjectFile::key),
                )
            } catch (error: Exception) {
                runCatching { persistProjectConfig(project, previousConfig) }
                runCatching { setPreferences(previousBookmarks) }
                null
            }
        }
    }

    private suspend fun inspectProjectFolderDeletion(
        project: PlatformFile,
        folderKey: FolderKey,
    ): ProjectFolderDeletionPreview? {
        if (folderKey == FolderKey.Base) return null
        val directory = resolveFolder(project, folderKey) ?: return null
        val entries = directory.list()
        val markdownFiles = entries
            .filter { child -> !child.isDirectory() && child.name.endsWith(".md", ignoreCase = true) }
            .map { file -> ProjectFile(folderKey.file(file.name), file) }
        val unsupportedEntries = entries
            .filter { child -> child.isDirectory() ||
                (!child.name.endsWith(".md", ignoreCase = true) && !isManagedFolderIdentity(child)) }
            .map { it.name }
            .sorted()
        return ProjectFolderDeletionPreview(
            folder = ProjectFolder(folderKey, directory),
            markdownFiles = markdownFiles,
            unsupportedEntries = unsupportedEntries,
        )
    }

    /**
     * 저장소를 선택
     * @return 저장소
     */
    suspend fun pickVault(): PlatformFile? = withContext(Dispatchers.IO) {
        try {
            val initVault = getPreferences().vaultData
            val vault = FileKit.openDirectoryPicker(
                directory = initVault,
            )
            vault?.let{
                setPreferences(Bookmarks(vaultData = it)).vaultData
            }
        } catch (e: Exception) {
            throw e
        }
    }

    /** workspaceOpenMutex 안에서만 호출한다. 화면 교체 후에도 이미 시작한 진입을 완료한다. */
    private suspend fun activateProject(project: PlatformFile) = withContext(Dispatchers.IO + NonCancellable) {
        projectIndexer.prepare(project)
        try {
            val preferences = getPreferences()
            preferences.vaultData?.let { vault -> runCatching { rememberProject(vault, project) } }
            setPreferences(preferences.copy(projectData = project, fileData = null,
                fileRelativePath = null, workspaceKind = WorkspaceKind.PROJECT))
            loadProjectConfig(project)
        } catch (e: Exception) {
            projectIndexer.fail(project, e)
            throw e
        }
    }

    suspend fun pickFile(projectFile: ProjectFile): PlatformFile = withContext(Dispatchers.IO) {
        val file = projectFile.platformFile
        setPreferences(
            getPreferences().copy(
                fileData = file,
                fileRelativePath = projectFile.key.relativePath,
            )
        ).fileData!!
    }

    /** 현재 작업 공간은 유지하고 더 이상 존재하지 않는 선택 파일 bookmark만 비운다. */
    suspend fun clearPickedFile() = withContext(Dispatchers.IO) {
        setPreferences(
            getPreferences().copy(
                fileData = null,
                fileRelativePath = null,
            ),
        )
    }

    private suspend fun readConfig(configFile: PlatformFile): ProjectConfig? = withContext(Dispatchers.IO) {
        val content = configFile.readString()
        if (content.isBlank()) return@withContext null

        configJson.decodeFromString(ProjectConfig.serializer(), content)
    }

    /**
     * 프로젝트 설정을 읽어 라이브 상태로 전환한다.
     * 빈 파일 또는 base 설정이 없는 구 설정은 기본값을 보완해 즉시 디스크에도 기록한다.
     */
    private suspend fun loadProjectConfig(project: PlatformFile): ProjectConfig? = withContext(Dispatchers.IO) {
        if (_bookmarks.value.workspaceKind == WorkspaceKind.GENERAL) return@withContext _projectConfig.value
        projectConfigMutex.withLock {
            val configFile = setConfig(project, PROJECT_CONFIG_FILE_NAME) ?: return@withLock null
            val stored = readConfig(configFile)
            val normalized = (stored ?: ProjectConfig()).withDefaultBaseFolder()

            if (stored != normalized) {
                persistConfig(configFile, normalized)
            }

            projectIndexer.index(project)

            if (!sameLocation(_bookmarks.value.projectData, project)) return@withLock null
            _projectConfig.value = normalized
            normalized
        }
    }

    /**
     * 파일의 마지막 수정 시각 (epoch millis). 조회 실패 시 null.
     * 외부(옵시디언 등) 변경 감지에 사용.
     */
    suspend fun lastModified(file: PlatformFile): Long? = withContext(Dispatchers.IO) {
        try {
            file.getLastModified()
        } catch (e: Exception) {
            null
        }
    }

    /** 선택된 작업 공간과 무관한 순수 읽기. ID·태그 생성이나 파일 쓰기를 하지 않는다. */
    suspend fun readMarkdown(file: PlatformFile): NoteFile = withContext(Dispatchers.IO) {
        NoteFile.parse(file.readString())
    }

    internal suspend fun inspectProjectMetadata(
        file: PlatformFile,
        requiredProjectTag: String?,
    ): ProjectMetadataUpdate = withContext(Dispatchers.IO) {
        val raw = NoteFile.parse(file.readString())
        val normalized = raw.withProjectMetadata(requiredProjectTag)
        val changed = raw.inject() != normalized.inject()
        ProjectMetadataUpdate(normalized, changed)
    }

    internal suspend fun ensureProjectMetadata(
        file: PlatformFile,
        requiredProjectTag: String?,
    ): ProjectMetadataUpdate = withContext(Dispatchers.IO) {
        inspectProjectMetadata(file, requiredProjectTag).also { update ->
            if (update.changed) file.writeString(update.noteFile.inject())
        }
    }

    /**
     * 파일 쓰기 (생성 & 수정)
     * @param file 선택한 파일
     * @param body 파일 내용
     */
    suspend fun write(file: PlatformFile, body: String) = withContext(Dispatchers.IO) {
        file.writeString(body)
    }

    /** 설정 전체를 현재 프로젝트에 저장하고 라이브 상태를 함께 갱신한다. */
    suspend fun writeConfig(projectConfig: ProjectConfig): ProjectConfig? = withContext(Dispatchers.IO) {
        projectConfigMutex.withLock {
            val project = _bookmarks.value.projectData ?: return@withLock null
            persistProjectConfig(project, projectConfig)
        }
    }

    /** 외부에서 `.machum.json`이 교체된 뒤 현재 프로젝트 설정과 인덱싱 상태를 다시 읽는다. */
    suspend fun reloadCurrentProjectConfig(): ProjectConfig? = withContext(Dispatchers.IO) {
        if (_bookmarks.value.workspaceKind == WorkspaceKind.GENERAL) return@withContext _projectConfig.value
        val project = _bookmarks.value.projectData ?: return@withContext null
        loadProjectConfig(project)
    }

    /** 현재 설정을 원자적으로 변경하고 저장한다. 설정이 로드되지 않았다면 null을 반환한다. */
    suspend fun updateProjectConfig(
        transform: (ProjectConfig) -> ProjectConfig,
    ): ProjectConfig? = withContext(Dispatchers.IO) {
        projectConfigMutex.withLock {
            val project = _bookmarks.value.projectData ?: return@withLock null
            val current = _projectConfig.value ?: return@withLock null
            persistProjectConfig(project, transform(current))
        }
    }

    /** 상대 경로에 해당하는 폴더 설정을 추가하거나 교체한다. 빈 경로는 base 폴더다. */
    suspend fun setFolderConfig(
        relativePath: String,
        folderConfig: FolderConfig,
    ): ProjectConfig? = updateProjectConfig { current ->
        current.copy(folders = current.folders + (relativePath to folderConfig))
    }

    private suspend fun persistProjectConfig(
        project: PlatformFile,
        projectConfig: ProjectConfig,
    ): ProjectConfig? {
        if (_bookmarks.value.workspaceKind == WorkspaceKind.GENERAL) {
            return projectConfig.copy(folders = projectConfig.folders.mapValues {
                FolderConfig(type = FolderType.GENERAL)
            }, fileIds = emptyMap()).also { _projectConfig.value = it }
        }
        val configFile = setConfig(project, PROJECT_CONFIG_FILE_NAME) ?: return null
        val normalized = projectConfig.withDefaultBaseFolder()
        persistConfig(configFile, normalized)

        if (!sameLocation(_bookmarks.value.projectData, project)) return null
        _projectConfig.value = normalized
        return normalized
    }

    private suspend fun persistConfig(configFile: PlatformFile, projectConfig: ProjectConfig) {
        val content = configJson.encodeToString(ProjectConfig.serializer(), projectConfig)
        configFile.writeString(content)
    }

    suspend fun writeMarkdown(file: PlatformFile, noteFile: NoteFile) = withContext(Dispatchers.IO) {
        file.writeString(noteFile.inject())
    }

    /**
     * 파일 삭제
     * @param file 삭제할 파일
     * @return 성공 여부
     */
    suspend fun delete(file: PlatformFile) = withContext(Dispatchers.IO) {
        try {
            file.delete()
            true
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 저장소 생성 및 초기 세팅
     * @param parentDirectory 저장소가 생성될 위치
     * @param name 저장소의 이름
     * @return 저장소
     */
    suspend fun setVault(parentDirectory: PlatformFile, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        createFolder(parentDirectory, name)?.let{
            setPreferences(Bookmarks(vaultData = it)).vaultData
        }
    }

    /**
     * 프로젝트 생성 및 초기 세팅
     * @param name 프로젝트의 이름
     * @return 프로젝트
     */
    suspend fun setProject(name: String): PlatformFile? = workspaceOpenMutex.withLock {
        createProject(name)?.let{
            activateProject(it)
            it
        }
    }

    /** Creates and registers a Vault child without changing the selected workspace. */
    suspend fun createGeneralWorkspace(vault: PlatformFile): PlatformFile = workspaceOpenMutex.withLock {
        withContext(Dispatchers.IO) {
            check(_bookmarks.value.vaultData?.let { sameLocation(it, vault) } == true) {
                "Vault가 변경되었습니다. 목록을 새로고침해 주세요."
            }
            val names = vault.list().map { it.name }
            var index = 0
            var name = "무제"
            while (names.any { it.equals(name, ignoreCase = true) }) {
                index += 1
                name = "무제 $index"
            }
            // A malformed/unwritable existing classification is never replaced by defaults.
            vaultConfigMutex.withLock { readVaultConfigUnlocked(vault) }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val created = createFolderExclusive(vault, name)
                    ?: error("폴더를 만들지 못했습니다. 목록을 새로고침해 주세요.")
                check(created.name == name && created.isDirectory() &&
                    vault.list().any { sameLocation(it, created) && it.name == name }) {
                    "요청한 이름과 다른 폴더가 생성되었습니다: ${created.name}. 목록에서 확인해 주세요."
                }
                try {
                    updateWorkspaceVaultConfig(vault) { it.copy(generalFolders = it.generalFolders + name) }
                } catch (error: Exception) {
                    // No recursive deletion: an external writer may already have added content.
                    val removed = runCatching { deleteEmptyFolderExclusive(created) }.getOrDefault(false)
                    throw IllegalStateException(
                        (if (removed) "일반 폴더 등록에 실패하여 새 빈 폴더를 정리했습니다."
                        else "일반 폴더 등록에 실패했습니다. 생성된 폴더를 확인해 주세요: $created") + " ${error.message}",
                        error,
                    )
                }
                created
            }
        }
    }

    /** Explicit selection-screen target. Unselected workspaces never replace current bookmarks. */
    suspend fun renameWorkspace(
        vault: PlatformFile,
        directory: PlatformFile,
        name: String,
    ): PlatformFile? = workspaceOpenMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_bookmarks.value.vaultData?.let { sameLocation(it, vault) } != true) return@withContext null
            val child = requireVaultChild(directory, listProject(vault))
            val targetName = name.trim()
            if (!isValidProjectFolderName(targetName)) return@withContext null
            if (targetName == child.name) return@withContext child
            if (targetName.equals(child.name, ignoreCase = true) || vault.list().any {
                    it.name.equals(targetName, ignoreCase = true)
                }) return@withContext null
            val setup = workspaceSetup(child)
            if (setup == WorkspaceSetup.NEEDS_CONFIRMATION) return@withContext null
            val oldName = child.name
            val oldBookmarks = getPreferences()
            val active = oldBookmarks.projectData?.let { sameLocation(it, child) } == true
            val oldProjectConfig = _projectConfig.value
            val oldConfig = vaultConfigMutex.withLock { readVaultConfigUnlocked(vault) }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) rename@{
                val renamed = renameDirectoryExact(vault, child, targetName) ?: return@rename null
                check(renamed.name == targetName && renamed.isDirectory()) {
                    "폴더 이름 변경 결과가 요청과 다릅니다: $renamed. 목록을 새로고침해 주세요."
                }
                var configUpdated = false
                try {
                    updateWorkspaceVaultConfig(vault) { config ->
                        config.copy(
                            generalFolders = if (setup == WorkspaceSetup.GENERAL)
                                config.generalFolders - oldName + targetName else config.generalFolders,
                            lastProject = if (config.lastProject == oldName) targetName else config.lastProject,
                            suspendedProjects = config.suspendedProjects[oldName]?.let {
                                config.suspendedProjects - oldName + (targetName to it)
                            } ?: config.suspendedProjects,
                        )
                    }
                    configUpdated = true
                    if (active) {
                        val selectedFile = oldBookmarks.fileRelativePath?.let { resolveRelativeFile(renamed, it) }
                        check(oldBookmarks.fileRelativePath == null || selectedFile != null) {
                            "이름 변경 후 선택한 문서를 찾지 못했습니다."
                        }
                        setPreferences(oldBookmarks.copy(
                            projectData = renamed,
                            fileData = selectedFile,
                            fileRelativePath = oldBookmarks.fileRelativePath.takeIf { selectedFile != null },
                        ))
                        if (setup == WorkspaceSetup.GENERAL) {
                            _projectConfig.value = oldProjectConfig ?: ProjectConfig(folders = listFolders(renamed).associate {
                                it.key.relativePath to FolderConfig(type = FolderType.GENERAL)
                            })
                        }
                    }
                } catch (error: Exception) {
                    val restored = renameDirectoryExact(vault, renamed, oldName)
                    if (restored != null && configUpdated) {
                        runCatching { updateWorkspaceVaultConfig(vault) { config ->
                            config.copy(
                                generalFolders = if (setup == WorkspaceSetup.GENERAL)
                                    config.generalFolders - targetName + oldName else config.generalFolders,
                                lastProject = if (config.lastProject == targetName) oldConfig.lastProject else config.lastProject,
                                suspendedProjects = config.suspendedProjects[targetName]?.let {
                                    config.suspendedProjects - targetName + (oldName to it)
                                } ?: config.suspendedProjects,
                            )
                        } }.onFailure { error.addSuppressed(it) }
                    }
                    throw IllegalStateException(
                        (if (restored != null) "이름 변경 기록에 실패하여 폴더 이름을 되돌렸습니다."
                        else "이름 변경 기록에 실패했습니다. 실제 폴더 위치를 확인해 주세요: $renamed") + " ${error.message}",
                        error,
                    )
                }
                if (setup == WorkspaceSetup.PROJECT) {
                    try {
                        synchronizeProjectNameTag(renamed, oldName, failOnError = true)
                    } catch (error: Exception) {
                        throw IllegalStateException("폴더 이름은 변경되었지만 프로젝트 태그 갱신에 실패했습니다: $renamed", error)
                    }
                    if (active) {
                        projectIndexer.prepare(renamed)
                        loadProjectConfig(renamed)
                    }
                }
                renamed
            }
        }
    }

    private suspend fun initializeNewProject(project: PlatformFile): Boolean {
        DEFAULT_PROJECT_FOLDERS.forEach { template ->
            createFolder(project, template.name) ?: return false
        }
        val configFile = setConfig(project, PROJECT_CONFIG_FILE_NAME) ?: return false
        persistConfig(configFile, defaultProjectConfig())
        return true
    }

    private suspend fun rollbackNewProject(project: PlatformFile) {
        runCatching {
            project.list().forEach { child -> child.delete() }
            project.delete()
        }
    }

    suspend fun setFile(project: PlatformFile): PlatformFile = withContext(Dispatchers.IO) {
        try {
            val config = _projectConfig.value ?: loadProjectConfig(project)
            val folderConfig = config?.folders?.get(BASE_FOLDER_PATH) ?: DEFAULT_BASE_FOLDER_CONFIG
            val baseFolder = ProjectFolder(FolderKey.Base, project)
            val files = listProjectFiles(baseFolder).let { projectFiles ->
                if (folderConfig.isPlot) {
                    projectFiles
                        .map { projectFile ->
                            val noteFile = NoteFile.parse(projectFile.platformFile.readString())
                            PlotFileEntry(
                                projectFile = projectFile,
                                stage = noteFile.plotStage,
                                order = projectFile.plotOrder(),
                            )
                        }
                        .sortedForPlot()
                        .map(PlotFileEntry::projectFile)
                } else {
                    projectFiles.sortedFor(folderConfig)
                }
            }
            files.lastOrNull()
                ?.let { projectFile ->
                    setPreferences(
                        getPreferences().copy(
                            fileData = projectFile.platformFile,
                            fileRelativePath = projectFile.key.relativePath,
                        )
                    ).fileData
                }
                ?:run{
                    val name = when {
                        folderConfig.isPlot -> PlotStage.PROLOGUE.fileName(PlotStage.FIRST_ORDER, "제목")
                        folderConfig.type == FolderType.DEFAULT -> "0. 제목"
                        else -> "제목"
                    }
                    createProjectFile(baseFolder, name)
                        ?.also { created ->
                            if (folderConfig.isPlot) {
                                val noteFile = readMarkdown(created.platformFile)
                                    .withPlotStage(PlotStage.PROLOGUE)
                                writeMarkdown(created.platformFile, noteFile)
                            }
                        }
                        ?.let {
                            setPreferences(
                                getPreferences().copy(
                                    fileData = it.platformFile,
                                    fileRelativePath = it.key.relativePath,
                                )
                            ).fileData
                        }
                        ?:throw Exception()
                }
        } catch (e: Exception) {
            throw e
        }
    }

    suspend fun renameProject(project: PlatformFile, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        if (_bookmarks.value.workspaceKind == WorkspaceKind.GENERAL) return@withContext null
        val vault = _bookmarks.value.vaultData ?: return@withContext null
        val selectedProject = _bookmarks.value.projectData ?: return@withContext null
        if (!sameLocation(selectedProject, project)) return@withContext null

        val projectName = name.trim()
        if (!isValidProjectFolderName(projectName)) return@withContext null
        if (projectName == project.name) return@withContext project
        // 플랫폼별 대소문자 처리 차이로 인한 충돌을 피한다.
        if (projectName.equals(project.name, ignoreCase = true)) return@withContext null
        if (vault.list().any { child ->
                !sameLocation(child, project) && child.name.equals(projectName, ignoreCase = true)
            }
        ) {
            return@withContext null
        }

        val previousName = project.name
        val previousBookmarks = getPreferences()
        val previousVaultConfig = loadVaultConfig(vault, listProject(vault))
        val renamedProject = renameDirectoryExact(vault, project, projectName)
            ?: return@withContext null
        val selectedFile = previousBookmarks.fileRelativePath
            ?.let { relativePath -> resolveRelativeFile(renamedProject, relativePath) }

        projectIndexer.prepare(renamedProject)
        val vaultConfigUpdated = runCatching {
            rememberProject(vault, renamedProject, previousName)
        }.isSuccess
        val bookmarkUpdated = runCatching {
            setPreferences(
                previousBookmarks.copy(
                    projectData = renamedProject,
                    fileData = selectedFile,
                    fileRelativePath = previousBookmarks.fileRelativePath
                        .takeIf { selectedFile != null },
                )
            )
        }.isSuccess
        if (!bookmarkUpdated) {
            renameDirectoryExact(vault, renamedProject, previousName)
            if (vaultConfigUpdated) {
                runCatching { updateVaultConfig(vault) { previousVaultConfig } }
            }
            projectIndexer.reset()
            return@withContext null
        }

        // 프로젝트명은 관리 태그이므로 기존 이름은 제거하고 새 이름을 첫 태그로 둔다.
        synchronizeProjectNameTag(
            project = renamedProject,
            previousName = previousName,
        )
        runCatching { loadProjectConfig(renamedProject) }
            .onFailure { error -> projectIndexer.fail(renamedProject, error) }
        renamedProject
    }

    private suspend fun synchronizeProjectNameTag(
        project: PlatformFile,
        previousName: String,
        failOnError: Boolean = false,
    ) {
        val previousTag = normalizeTag(previousName)
        val updatedTag = normalizeTag(project.name)
        listFolders(project)
            .flatMap { folder -> listProjectFiles(folder) }
            .forEach { projectFile ->
                val synchronized = runCatching {
                    val original = NoteFile.parse(projectFile.platformFile.readString())
                    val withId = original.ensureId()
                    val updatedTags = buildList {
                        add(updatedTag)
                        withId.tags
                            .filterNot { tag -> tag == previousTag || tag == updatedTag }
                            .forEach { tag -> if (tag !in this) add(tag) }
                    }
                    val updated = withId.withTags(updatedTags)
                    if (updated.inject() != original.inject()) {
                        projectFile.platformFile.writeString(updated.inject())
                    }
                }
                if (failOnError) synchronized.getOrThrow()
            }
    }

    suspend fun renameFile(projectFile: ProjectFile, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        if (!isValidProjectFileTitle(name)) return@withContext null
        val project = _bookmarks.value.projectData ?: return@withContext null
        val parent = resolveFolder(project, projectFile.key.folder) ?: return@withContext null
        renameMarkdownExact(parent, projectFile.platformFile, name)
    }

    private fun resolveFolder(project: PlatformFile, key: FolderKey): PlatformFile? {
        if (key == FolderKey.Base) return project
        if ('/' in key.relativePath) return null
        return project.list().find { it.isDirectory() && it.name == key.relativePath }
    }

    private fun resolveRelativeFile(project: PlatformFile, relativePath: String): PlatformFile? {
        val key = runCatching { FileKey.of(relativePath) }.getOrNull() ?: return null
        val parent = resolveFolder(project, key.folder) ?: return null
        return parent.list().find { !it.isDirectory() && it.name == key.fileName }
    }

    private fun sameLocation(first: PlatformFile?, second: PlatformFile?): Boolean =
        first?.toString() == second?.toString()

    private suspend fun applyMarkdownOrderTransaction(
        folder: ProjectFolder,
        works: List<MarkdownOrderWork>,
    ): Boolean = projectConfigMutex.withLock {
        val project = _bookmarks.value.projectData ?: return@withLock false
        // fileIds는 문서 identity의 일부다. 설정이 로드되지 않은 상태에서 파일명만
        // 바뀌는 반쪽 성공을 만들지 않도록 mutation 전에 실패한다.
        val previousConfig = _projectConfig.value ?: return@withLock false
        val previousBookmarks = _bookmarks.value
        val rollbackBaseNames = allocateRollbackBaseNames(folder, works)

        try {
            works.forEach { work ->
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    work.temporaryBaseName,
                ) ?: error("temporary order rename failed")
            }
            works.forEach { work ->
                work.updatedNoteFile?.let { noteFile ->
                    writeMarkdown(work.currentFile, noteFile)
                }
            }
            works.forEach { work ->
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    work.finalBaseName,
                ) ?: error("final order rename failed")
            }

            val pathChanges = works.associate { work ->
                work.oldKey.relativePath to folder.key.file(work.currentFile.name).relativePath
            }
            val updatedConfig = previousConfig.copy(
                fileIds = previousConfig.fileIds.mapValues { (_, relativePath) ->
                    pathChanges[relativePath] ?: relativePath
                },
            )
            persistProjectConfig(project, updatedConfig) ?: error("config save failed")

            val selectedPath = previousBookmarks.fileRelativePath
            val updatedSelectedPath = selectedPath?.let(pathChanges::get)
            if (updatedSelectedPath != null) {
                val selectedFile = works
                    .firstOrNull { work -> work.oldKey.relativePath == selectedPath }
                    ?.currentFile
                    ?: error("renamed selected file missing")
                setPreferences(
                    previousBookmarks.copy(
                        fileData = selectedFile,
                        fileRelativePath = updatedSelectedPath,
                    ),
                )
            }
            true
        } catch (error: Exception) {
            withContext(NonCancellable) {
                rollbackMarkdownOrder(folder, works, rollbackBaseNames)
                runCatching { persistProjectConfig(project, previousConfig) }
                runCatching { setPreferences(previousBookmarks) }
            }
            if (error is CancellationException) throw error
            false
        }
    }

    private suspend fun rollbackMarkdownOrder(
        folder: ProjectFolder,
        works: List<MarkdownOrderWork>,
        rollbackBaseNames: List<String>,
    ) {
        works.forEachIndexed { index, work ->
            runCatching {
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    rollbackBaseNames[index],
                ) ?: work.currentFile
            }
        }
        works.forEach { work ->
            runCatching {
                work.originalMarkdown?.let { raw ->
                    write(work.currentFile, raw)
                }
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    work.originalBaseName,
                ) ?: work.currentFile
            }
        }
    }

    private fun allocateRollbackBaseNames(
        folder: ProjectFolder,
        works: List<MarkdownOrderWork>,
    ): List<String> {
        val reservedFileNames = folder.platformFile.list()
            .mapTo(mutableSetOf()) { file -> file.name.lowercase() }
        works.forEach { work ->
            reservedFileNames += "${work.temporaryBaseName}.md".lowercase()
            reservedFileNames += "${work.finalBaseName}.md".lowercase()
        }
        return works.mapIndexed { index, _ ->
            var attempt = 0
            var candidate: String
            do {
                candidate = buildString {
                    append(".machum-order-rollback-")
                    append(index)
                    if (attempt > 0) append("-").append(attempt)
                }
                attempt++
            } while ("$candidate.md".lowercase() in reservedFileNames)
            reservedFileNames += "$candidate.md".lowercase()
            candidate
        }
    }
}

private val defaultOrderPrefixRegex = Regex("""^\d+\.\s*""")

private fun ProjectFile.defaultOrderTitle(): String =
    platformFile.nameWithoutExtension.replace(defaultOrderPrefixRegex, "")

private data class MarkdownOrderWork(
    val oldKey: FileKey,
    val originalBaseName: String,
    val originalMarkdown: String?,
    val updatedNoteFile: NoteFile?,
    val finalBaseName: String,
    val temporaryBaseName: String,
    var currentFile: PlatformFile,
)

data class DefaultOrderUpdate(
    val oldKey: FileKey,
    val projectFile: ProjectFile,
)

data class FolderRenameUpdate(
    val previousKey: FolderKey,
    val projectFolder: ProjectFolder,
    val filesByPreviousKey: Map<FileKey, ProjectFile>,
    val projectConfig: ProjectConfig,
    val selectedFileKey: FileKey?,
)

/** 실패가 아니라 원상복구까지 실패한 상태다. 호출자는 성공이나 정상 취소로 숨기지 않는다. */
class FolderRenameRollbackException(
    previousPath: String,
    updatedPath: String,
    cause: Throwable,
    rollbackFailures: List<Throwable>,
) : IllegalStateException(
    "폴더 이름 변경을 완전히 되돌리지 못했습니다 ($previousPath → $updatedPath). " +
        "추가 편집을 멈추고 실제 폴더를 확인한 뒤 작업 공간을 다시 열어 주세요.",
    cause,
) {
    init {
        rollbackFailures.forEach(::addSuppressed)
    }
}

data class ProjectFolderDeletionPreview(
    val folder: ProjectFolder,
    val markdownFiles: List<ProjectFile>,
    val unsupportedEntries: List<String>,
) {
    val canDelete: Boolean get() = unsupportedEntries.isEmpty()
}

data class FolderDeletionUpdate(
    val folderKey: FolderKey,
    val deletedFileKeys: List<FileKey>,
)

private fun String.replaceFolderPrefix(previousKey: FolderKey, updatedKey: FolderKey): String = when {
    this == previousKey.relativePath -> updatedKey.relativePath
    startsWith("${previousKey.relativePath}/") ->
        updatedKey.relativePath + removePrefix(previousKey.relativePath)
    else -> this
}

internal data class ProjectMetadataUpdate(
    val noteFile: NoteFile,
    val changed: Boolean,
)

internal fun mergeManagedTags(
    existingTags: List<String>,
    previousManagedTags: List<String>,
    updatedManagedTags: List<String>,
): List<String> = buildList {
    val previousManaged = previousManagedTags.toSet()
    existingTags.filterNot { it in previousManaged }.forEach { tag ->
        if (tag !in this) add(tag)
    }
    updatedManagedTags.forEach { tag ->
        if (tag !in this) add(tag)
    }
}

/** 최신 본문을 기준으로 관리 태그만 교체한다. 변경이 없으면 원본 객체/직렬화를 보존한다. */
internal fun NoteFile.withManagedTagChanges(
    projectName: String,
    previousTags: List<String>,
    updatedTags: List<String>,
): NoteFile {
    if (previousTags == updatedTags) return this
    val projectTag = normalizeTag(projectName)
    val merged = mergeManagedTags(tags, previousTags, updatedTags)
    val required = (listOf(projectTag) + merged.filterNot { it == projectTag }).distinct()
    val identified = ensureId()
    return if (tags == required) identified else identified.withTags(required)
}

internal fun isValidProjectFolderName(name: String): Boolean {
    return isValidProjectEntryName(name)
}

internal fun isValidProjectFileTitle(title: String): Boolean {
    if (title.endsWith(".md", ignoreCase = true)) return false
    return isValidProjectEntryName(title)
}

private fun isValidProjectEntryName(name: String): Boolean {
    if (name.isBlank() || name != name.trim()) return false
    if (name == "." || name == ".." || name.startsWith('.')) return false
    if (name.endsWith('.') || name.any { it.isISOControl() }) return false
    if (name.any { it in PROJECT_FOLDER_INVALID_CHARACTERS }) return false

    val deviceName = name.substringBefore('.').uppercase()
    return deviceName !in PROJECT_FOLDER_RESERVED_NAMES
}

private const val PROJECT_FOLDER_INVALID_CHARACTERS = "<>:\"/\\|?*"
private val PROJECT_FOLDER_RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { index ->
        add("COM$index")
        add("LPT$index")
    }
}

/**
 * 파일/디렉토리 생성
 * @param parentDirectory 폴더를 생성할 부모 디렉토리
 * @param name 생성할 파일/폴더명
 * @return 생성된 디렉토리
 */
internal expect suspend fun FileManager.createFile(parentDirectory: PlatformFile, name: String, content: String = ""): PlatformFile?

internal expect suspend fun FileManager.createFolder(parentDirectory: PlatformFile, name: String): PlatformFile?

/** Returns only a newly created child. Existing files/directories are never reused. */
internal expect suspend fun FileManager.createFolderExclusive(parentDirectory: PlatformFile, name: String): PlatformFile?

/** Removes an empty directory only; never removes children. */
internal expect suspend fun FileManager.deleteEmptyFolderExclusive(directory: PlatformFile): Boolean

internal expect suspend fun FileManager.renameMarkdownExact(parentDirectory: PlatformFile, file: PlatformFile, name: String): PlatformFile?

internal expect suspend fun FileManager.renameDirectoryExact(
    parentDirectory: PlatformFile,
    directory: PlatformFile,
    name: String,
): PlatformFile?

internal expect suspend fun FileManager.deleteDirectoryExact(directory: PlatformFile): Boolean

internal expect suspend fun FileManager.setConfig(
    parentDirectory: PlatformFile,
    fileName: String,
): PlatformFile?

internal expect suspend fun FileManager.validPermission(file: PlatformFile): Boolean

internal expect fun PlatformFile.getLastModified(): Long?

internal fun PlatformFile.Companion.fromBookmarkDataWithValidate(bytes: ByteArray): PlatformFile? {
    val data = PlatformFile.fromBookmarkData(bytes)
    return if (data.exists()) data else null
}

internal fun PlatformFile.markdownName(): MarkdownName {
    val parts = nameWithoutExtension.split(". ", limit = 2)
    return if (parts.size == 2) {
        MarkdownName(parts[0], parts[1])
    } else {
        MarkdownName("", parts[0])
    }
}

data class Bookmarks(
    val vaultData: PlatformFile? = null,
    val projectData: PlatformFile? = null,
    val fileData: PlatformFile? = null,
    val fileRelativePath: String? = null,
    val workspaceKind: WorkspaceKind = WorkspaceKind.PROJECT,
)

data class MarkdownName(val numbering: String, val title: String)

/** Transient request state: a created file exists but its initial content was not fully written. */
class FileCreationIncompleteException internal constructor(
    val file: PlatformFile,
    internal val expectedContent: String,
    internal val observedContent: String?,
    cause: Exception,
) : IllegalStateException("파일 생성됨, 내용 기록 미완료: $file. ${cause.message}", cause)

internal suspend fun incompleteFileCreation(
    file: PlatformFile,
    expectedContent: String,
    cause: Exception,
): FileCreationIncompleteException = withContext(NonCancellable) {
    FileCreationIncompleteException(file, expectedContent, runCatching { file.readString() }.getOrNull(), cause)
}
