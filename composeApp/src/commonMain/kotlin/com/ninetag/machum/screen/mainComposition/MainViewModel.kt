package com.ninetag.machum.screen.mainComposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.effectiveAutoTags
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.ProjectFolder
import com.ninetag.machum.external.PlotFileEntry
import com.ninetag.machum.external.PlotOrderAssignment
import com.ninetag.machum.external.isValidProjectFileTitle
import com.ninetag.machum.external.nextDefaultFileName
import com.ninetag.machum.external.nextPlotFileName
import com.ninetag.machum.external.plotOrder
import com.ninetag.machum.external.sortedForPlot
import com.ninetag.machum.external.sortedFor
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MainViewModel(private val fileManager: FileManager) : ViewModel() {
    val bookmarks: StateFlow<Bookmarks> = fileManager.bookmarks
    val projectConfig: StateFlow<ProjectConfig?> = fileManager.projectConfig

    private val _projectList = MutableStateFlow<List<PlatformFile>>(emptyList())
    val projectList: StateFlow<List<PlatformFile>> = _projectList.asStateFlow()

    private val _folderList = MutableStateFlow<List<ProjectFolder>>(emptyList())
    val folderList: StateFlow<List<ProjectFolder>> = _folderList.asStateFlow()

    private val _currentFolder = MutableStateFlow<ProjectFolder?>(null)
    val currentFolder: StateFlow<ProjectFolder?> = _currentFolder.asStateFlow()

    private val _fileList = MutableStateFlow<List<ProjectFile>>(emptyList())
    val fileList: StateFlow<List<ProjectFile>> = _fileList.asStateFlow()

    private val _plotFileEntries = MutableStateFlow<List<PlotFileEntry>>(emptyList())
    val plotFileEntries: StateFlow<List<PlotFileEntry>> = _plotFileEntries.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // 프로젝트 상대 경로를 key로 사용해 하위 폴더의 동명 파일을 구분한다.
    private val _noteFileCache = MutableStateFlow<Map<FileKey, NoteFile>>(emptyMap())
    val noteFileCache: StateFlow<Map<FileKey, NoteFile>> = _noteFileCache.asStateFlow()

    // 외부(옵시디언 등) 변경 감지용 — 파일별로 마지막으로 "인지한" 수정 시각.
    // 앱 자신의 쓰기 직후에도 갱신하여, 폴링이 자기 쓰기를 외부 변경으로 오인하지 않게 한다.
    private val knownModified = mutableMapOf<FileKey, Long>()
    private val knownProjectFiles = mutableMapOf<FileKey, ProjectFile>()

    private var activeProjectLocation: String? = null
    private var activeVaultLocation: String? = null

    // 앱/창 활성 상태 — 활성일 때만 폴링 (Phase 2) + 활성 전환 시 즉시 1회 검사 (Phase 1)
    private val _active = MutableStateFlow(false)

    private val saveCoordinator = DebouncedSaveCoordinator<FileKey, PendingWrite>(
        scope = viewModelScope,
        debounceMillis = SAVE_DEBOUNCE_MS,
    ) { fileKey, pendingWrite ->
        val file = pendingWrite.projectFile.platformFile
        fileManager.writeMarkdown(file, pendingWrite.noteFile)
        // 자기 쓰기 mtime 기록 → 폴링이 외부 변경으로 오인하지 않도록
        fileManager.lastModified(file)?.let { knownModified[fileKey] = it }
    }

    private val folderSettingsService = FolderSettingsService(
        fileManager = fileManager,
        flushPendingWrites = saveCoordinator::flush,
    )

    init {
        viewModelScope.launch {
            fileManager.bookmarks.collect { bookmarks ->
                val vault = bookmarks.vaultData
                if (vault == null) {
                    _projectList.value = emptyList()
                    activeVaultLocation = null
                } else {
                    val vaultLocation = vault.toString()
                    if (activeVaultLocation != vaultLocation) {
                        activeVaultLocation = vaultLocation
                        _projectList.value = fileManager.listProject(vault)
                    }
                }

                val project = bookmarks.projectData
                if (project == null) {
                    if (activeProjectLocation != null) {
                        clearProjectState()
                        activeProjectLocation = null
                    }
                    return@collect
                }
                val projectLocation = project.toString()
                val projectChanged = activeProjectLocation != projectLocation
                if (projectChanged) {
                    clearProjectState()
                    activeProjectLocation = projectLocation
                }
                if (projectChanged || _folderList.value.isEmpty()) {
                    val preferredKey = bookmarks.fileRelativePath
                        ?.let { runCatching { FileKey.of(it) }.getOrNull() }
                    refreshFoldersAndFiles(project, preferredKey, cancelRemoved = false)
                } else {
                    val index = bookmarks.fileData?.let { selected ->
                        _fileList.value.indexOfFirst {
                            it.platformFile.toString() == selected.toString()
                        }
                    } ?: -1
                    if (index >= 0) _currentIndex.value = index
                }
            }
        }

        // 프로젝트 설정 변경에 따른 현재 목록 재정렬은 ViewModel 수명 동안 한 번만 구독한다.
        viewModelScope.launch {
            projectConfig.filterNotNull().collect {
                val folder = _currentFolder.value ?: return@collect
                val currentKey = _fileList.value.getOrNull(_currentIndex.value)?.key
                updateFileList(
                    files = sortedFiles(folder),
                    preferredKey = currentKey,
                    cancelRemoved = false,
                )
            }
        }

        // 외부 변경 감지: 활성(포커스) 상태에서만 동작.
        // 활성 전환 즉시 1회 검사(Phase 1) → 이후 주기 폴링(Phase 2). 비활성 시 collectLatest 가 루프를 취소.
        viewModelScope.launch {
            _active.collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    checkExternalChanges()
                    delay(POLL_INTERVAL_MS.milliseconds)
                }
            }
        }
    }

    /** 앱/창 포커스 상태 전달 (MainScreen 의 LocalWindowInfo.isWindowFocused) */
    fun setActive(active: Boolean) {
        _active.value = active
    }

    fun resetFileManager() {
        viewModelScope.launch {
            fileManager.reset()
        }
    }

    fun refreshProjectList() {
        viewModelScope.launch {
            val vault = bookmarks.value.vaultData ?: return@launch
            _projectList.value = fileManager.listProject(vault)
        }
    }

    fun selectProject(project: PlatformFile) {
        fileManager.pickProject(project)
    }

    fun navigateToProjectRoot() {
        selectFolder(FolderKey.Base)
    }

    fun selectFolder(folderKey: FolderKey) {
        viewModelScope.launch {
            val folder = _folderList.value.find { it.key == folderKey } ?: return@launch
            _currentFolder.value = folder
            val files = sortedFiles(folder)
            updateFileList(files, preferredKey = null, cancelRemoved = false)
            files.firstOrNull()?.let { fileManager.pickFile(it) }
        }
    }

    fun selectFile(fileKey: FileKey) {
        viewModelScope.launch {
            val index = _fileList.value.indexOfFirst { it.key == fileKey }
            if (index < 0) return@launch
            _currentIndex.value = index
            fileManager.pickFile(_fileList.value[index])
        }
    }

    fun createFileInCurrentFolder(title: String) {
        viewModelScope.launch {
            val folder = _currentFolder.value ?: return@launch
            if (!isValidProjectFileTitle(title)) return@launch
            val existing = sortedFiles(folder)
            val config = folderConfig(folder.key)
            if (config.isPlot) return@launch
            val fileName = when (config.type) {
                FolderType.DEFAULT -> existing.nextDefaultFileName(
                    title = title,
                    startAt = if (folder.key == FolderKey.Base) 0 else 1,
                )
                FolderType.GENERAL -> title
            }
            if (existing.any { it.key.fileName.equals("$fileName.md", ignoreCase = true) }) return@launch
            val created = fileManager.createProjectFile(folder, fileName) ?: return@launch
            val autoTags = autoTagsFor(folder.key)
            if (autoTags.isNotEmpty()) {
                val noteFile = fileManager.readMarkdown(created.platformFile)
                fileManager.writeMarkdown(
                    created.platformFile,
                    noteFile.withTags((noteFile.tags + autoTags).distinct()),
                )
            }
            val freshFiles = sortedFiles(folder)
            updateFileList(freshFiles, preferredKey = created.key, cancelRemoved = false)
            fileManager.pickFile(created)
        }
    }

    fun createPlotFile(stage: PlotStage, title: String) {
        viewModelScope.launch {
            val folder = _currentFolder.value ?: return@launch
            if (!folderConfig(folder.key).isPlot) return@launch
            if (!isValidProjectFileTitle(title)) return@launch
            val entries = loadPlotEntries(folder)
            val fileName = entries.nextPlotFileName(stage, title)
            if (entries.any { it.projectFile.key.fileName.equals("$fileName.md", ignoreCase = true) }) {
                return@launch
            }
            val created = fileManager.createProjectFile(folder, fileName) ?: return@launch
            val autoTags = autoTagsFor(folder.key)
            val noteFile = fileManager.readMarkdown(created.platformFile)
                .withPlotStage(stage)
                .let { note ->
                    if (autoTags.isEmpty()) note else note.withTags((note.tags + autoTags).distinct())
                }
            fileManager.writeMarkdown(created.platformFile, noteFile)
            _noteFileCache.value += created.key to noteFile
            val freshFiles = sortedFiles(folder)
            updateFileList(freshFiles, preferredKey = created.key, cancelRemoved = false)
            fileManager.pickFile(created)
        }
    }

    suspend fun savePlotOrder(assignments: List<PlotOrderAssignment>): Boolean {
        val folder = _currentFolder.value ?: return false
        if (!folderConfig(folder.key).isPlot) return false
        return try {
            val assignmentKeys = assignments.mapTo(mutableSetOf()) { it.fileKey }
            saveCoordinator.flush(assignmentKeys)
            val selectedOldKey = _fileList.value.getOrNull(_currentIndex.value)?.key
            val updates = fileManager.applyPlotOrder(folder, assignments) ?: return false
            val keyChanges = updates.associate { it.oldKey to it.projectFile.key }

            updates.forEach { update ->
                saveCoordinator.cancel(update.oldKey)
                knownProjectFiles.remove(update.oldKey)
                knownModified.remove(update.oldKey)
            }
            _noteFileCache.value = _noteFileCache.value.toMutableMap().apply {
                updates.forEach { update ->
                    remove(update.oldKey)
                    this[update.projectFile.key] = update.noteFile
                }
            }
            updates.forEach { update ->
                knownProjectFiles[update.projectFile.key] = update.projectFile
                fileManager.lastModified(update.projectFile.platformFile)?.let { modified ->
                    knownModified[update.projectFile.key] = modified
                }
            }

            val preferredKey = selectedOldKey?.let { keyChanges[it] ?: it }
            val freshFiles = sortedFiles(folder)
            updateFileList(freshFiles, preferredKey = preferredKey, cancelRemoved = false)
            preferredKey
                ?.let { key -> freshFiles.find { it.key == key } }
                ?.let { fileManager.pickFile(it) }
            true
        } catch (error: Exception) {
            false
        }
    }

    fun createDirectory(name: String, folderConfig: FolderConfig) {
        viewModelScope.launch {
            val project = bookmarks.value.projectData ?: return@launch
            fileManager.createProjectFolder(name, folderConfig) ?: return@launch
            val currentKey = _fileList.value.getOrNull(_currentIndex.value)?.key
            refreshFoldersAndFiles(project, preferredKey = currentKey, cancelRemoved = false)
        }
    }

    /**
     * 열려있는(캐시된) 파일들의 외부 변경을 감지하여 자동 리로드한다.
     * - 파일 목록도 함께 갱신 (외부에서 파일 추가/삭제)
     * - mtime 이 마지막 인지 시각과 같으면 skip (자기 쓰기 포함)
     * - 내용이 실제로 다르면 캐시 교체 → EditorPage 의 value 가 바뀌어 에디터가 재파싱 (외부 우선)
     */
    private suspend fun checkExternalChanges() {
        val project = fileManager.bookmarks.value.projectData ?: return

        // 1. 폴더·파일 목록 갱신 (외부 추가/삭제 반영, 현재 FileKey 보존)
        refreshFoldersAndFiles(project, preferredKey = null, cancelRemoved = true)

        // 2. 캐시된 파일들의 내용 변경 감지
        for (projectFile in _fileList.value) {
            val key = projectFile.key
            val file = projectFile.platformFile
            val cached = _noteFileCache.value[key] ?: continue // 아직 안 연 파일은 skip
            val diskModified = fileManager.lastModified(file) ?: continue
            if (knownModified[key] == diskModified) continue    // 변화 없음 (자기 쓰기 포함)

            // external wins: 외부 mtime 변경을 보면 이 파일의 stale pending write를 먼저 취소.
            saveCoordinator.cancel(key)
            val fresh = fileManager.readMarkdown(file)
            knownModified[key] = fileManager.lastModified(file) ?: diskModified
            // mtime 은 달라졌지만 내용은 동일할 수 있음(자기 쓰기 레이스 등) → 실제 diff 일 때만 교체
            if (fresh.inject() != cached.inject()) {
                _noteFileCache.value += key to fresh
            }
        }
    }

    fun onPageChanged(index: Int) {
        viewModelScope.launch {
            val file = _fileList.value.getOrNull(index) ?: return@launch
            fileManager.pickFile(file)
        }
    }

    fun loadPage(projectFile: ProjectFile) {
        viewModelScope.launch {
            val key = projectFile.key
            val file = projectFile.platformFile
            knownProjectFiles[key] = projectFile
            if (_noteFileCache.value.containsKey(key)) return@launch
            val markdown = fileManager.readMarkdown(file)
            _noteFileCache.value += (key to markdown)
            fileManager.lastModified(file)?.let { knownModified[key] = it }
        }
    }

    fun updateBody(fileKey: FileKey, newBody: String) {
        val current = _noteFileCache.value[fileKey] ?: return
        if (current.body == newBody) return
        val updated = current.withBody(newBody)
        _noteFileCache.value += fileKey to updated
        val projectFile = knownProjectFiles[fileKey] ?: return
        saveCoordinator.schedule(fileKey, PendingWrite(projectFile, updated))
    }

    fun updateDirectoryConfig(folderKey: FolderKey, folderConfig: FolderConfig) {
        viewModelScope.launch {
            val project = bookmarks.value.projectData ?: return@launch
            val previousConfig = projectConfig.value ?: return@launch
            val result = folderSettingsService.update(
                project = project,
                previousConfig = previousConfig,
                folderKey = folderKey,
                folderConfig = folderConfig,
            ) ?: return@launch
            val updates = result.autoTagUpdates

            if (updates.isNotEmpty()) {
                _noteFileCache.value += updates.associate { it.projectFile.key to it.noteFile }
                updates.forEach { update ->
                    val key = update.projectFile.key
                    knownProjectFiles[key] = update.projectFile
                    fileManager.lastModified(update.projectFile.platformFile)?.let { modified ->
                        knownModified[key] = modified
                    }
                }
            }
        }
    }

    fun onRenameFile(projectFile: ProjectFile, newName: String) {
        val file = projectFile.platformFile
        if (file.nameWithoutExtension == newName) return
        viewModelScope.launch {
            val oldKey = projectFile.key
            saveCoordinator.cancel(oldKey)
            val renamed = fileManager.renameFile(projectFile, newName)
            if (renamed == null) {
                _noteFileCache.value[oldKey]?.let {
                    saveCoordinator.schedule(oldKey, PendingWrite(projectFile, it))
                }
                return@launch
            }
            val newKey = oldKey.rename(renamed.name)
            val renamedProjectFile = ProjectFile(newKey, renamed)
            knownProjectFiles.remove(oldKey)
            knownProjectFiles[newKey] = renamedProjectFile
            // 캐시 key 교체 (Markdown 인스턴스는 그대로 유지)
            val cached = _noteFileCache.value[oldKey]
            saveCoordinator.cancel(oldKey)
            if (cached != null) {
                _noteFileCache.value = _noteFileCache.value
                    .toMutableMap()
                    .also {
                        it.remove(oldKey)
                        it[newKey] = cached
                    }
            }
            // mtime 추적 key 도 교체 (stale 엔트리 방지)
            knownModified.remove(oldKey)
            fileManager.lastModified(renamed)?.let { knownModified[newKey] = it }
            if (cached != null) {
                saveCoordinator.schedule(newKey, PendingWrite(renamedProjectFile, cached))
            }
            fileManager.pickFile(renamedProjectFile)
        }
    }

    private suspend fun refreshFoldersAndFiles(
        project: PlatformFile,
        preferredKey: FileKey?,
        cancelRemoved: Boolean,
    ) {
        val previousFolderKey = _currentFolder.value?.key
        val folders = fileManager.listFolders(project)
        _folderList.value = folders

        val requestedFolderKey = preferredKey?.folder ?: previousFolderKey ?: FolderKey.Base
        val folder = folders.find { it.key == requestedFolderKey }
            ?: folders.find { it.key == FolderKey.Base }
            ?: return
        _currentFolder.value = folder

        val files = sortedFiles(folder)
        updateFileList(
            files = files,
            preferredKey = preferredKey,
            cancelRemoved = cancelRemoved,
        )
    }

    private fun updateFileList(
        files: List<ProjectFile>,
        preferredKey: FileKey?,
        cancelRemoved: Boolean,
    ) {
        val previousKeys = _fileList.value.mapTo(mutableSetOf()) { it.key }
        val freshKeys = files.mapTo(mutableSetOf()) { it.key }
        if (cancelRemoved) {
            val removedKeys = previousKeys - freshKeys
            removedKeys.forEach { key ->
                saveCoordinator.cancel(key)
                knownProjectFiles.remove(key)
                knownModified.remove(key)
            }
            if (removedKeys.isNotEmpty()) {
                _noteFileCache.value = _noteFileCache.value - removedKeys
            }
        }

        files.forEach { knownProjectFiles[it.key] = it }
        val currentKey = preferredKey ?: _fileList.value.getOrNull(_currentIndex.value)?.key
        _fileList.value = files
        _currentIndex.value = currentKey
            ?.let { key -> files.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
            ?: 0
    }

    private fun clearProjectState() {
        saveCoordinator.cancelAll()
        _folderList.value = emptyList()
        _currentFolder.value = null
        _fileList.value = emptyList()
        _plotFileEntries.value = emptyList()
        _currentIndex.value = 0
        _noteFileCache.value = emptyMap()
        knownModified.clear()
        knownProjectFiles.clear()
    }

    private suspend fun sortedFiles(folder: ProjectFolder): List<ProjectFile> {
        val config = folderConfig(folder.key)
        val files = fileManager.listProjectFiles(folder)
        if (!config.isPlot) {
            _plotFileEntries.value = emptyList()
            return files.sortedFor(config)
        }
        return loadPlotEntries(folder, files)
            .sortedForPlot()
            .also { _plotFileEntries.value = it }
            .map(PlotFileEntry::projectFile)
    }

    private suspend fun loadPlotEntries(
        folder: ProjectFolder,
        files: List<ProjectFile>? = null,
    ): List<PlotFileEntry> = (files ?: fileManager.listProjectFiles(folder)).map { projectFile ->
        val noteFile = _noteFileCache.value[projectFile.key]
            ?: fileManager.readMarkdown(projectFile.platformFile).also { loaded ->
                _noteFileCache.value += projectFile.key to loaded
            }
        PlotFileEntry(
            projectFile = projectFile,
            stage = noteFile.plotStage,
            order = projectFile.plotOrder(),
        )
    }

    private fun folderConfig(folderKey: FolderKey): FolderConfig =
        projectConfig.value?.folders?.get(folderKey.relativePath)
            ?: FolderConfig()

    private fun autoTagsFor(folderKey: FolderKey): List<String> {
        return projectConfig.value?.effectiveAutoTags(folderKey.relativePath).orEmpty()
    }

    companion object {
        // Phase 2 폴링 주기. 활성(포커스) 상태에서만 동작.
        private const val POLL_INTERVAL_MS = 1500L
        private const val SAVE_DEBOUNCE_MS = 500L
    }
}

private data class PendingWrite(
    val projectFile: ProjectFile,
    val noteFile: NoteFile,
)
