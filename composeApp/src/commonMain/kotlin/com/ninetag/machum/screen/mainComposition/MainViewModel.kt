package com.ninetag.machum.screen.mainComposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninetag.machum.commit.CommitPreview
import com.ninetag.machum.commit.CommitHistoryEntry
import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.FileLineDiff
import com.ninetag.machum.commit.ProjectCommitService
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
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
import com.ninetag.machum.external.ProjectFolderDeletionPreview
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

class MainViewModel(
    private val fileManager: FileManager,
    private val workspaceSaveCoordinator: WorkspaceSaveCoordinator,
) : ViewModel() {
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

    private val _hierarchyFolderContents =
        MutableStateFlow<Map<FolderKey, HierarchyFolderContent>>(emptyMap())
    val hierarchyFolderContents: StateFlow<Map<FolderKey, HierarchyFolderContent>> =
        _hierarchyFolderContents.asStateFlow()

    private val _pendingFolderDeletion = MutableStateFlow<ProjectFolderDeletionPreview?>(null)
    val pendingFolderDeletion: StateFlow<ProjectFolderDeletionPreview?> =
        _pendingFolderDeletion.asStateFlow()

    private val _projectRenameState = MutableStateFlow(ProjectRenameUiState())
    val projectRenameState: StateFlow<ProjectRenameUiState> = _projectRenameState.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // 프로젝트 상대 경로를 key로 사용해 하위 폴더의 동명 파일을 구분한다.
    // 캐시 부재를 로딩/실패와 혼동하지 않도록 파일별 상태를 한 곳에서 관리한다.
    private val _fileLoadStates = MutableStateFlow<Map<FileKey, FileLoadUiState>>(emptyMap())
    val fileLoadStates: StateFlow<Map<FileKey, FileLoadUiState>> = _fileLoadStates.asStateFlow()

    // 외부(옵시디언 등) 변경 감지용 — 파일별로 마지막으로 "인지한" 수정 시각.
    // 앱 자신의 쓰기 직후에도 갱신하여, 폴링이 자기 쓰기를 외부 변경으로 오인하지 않게 한다.
    private val knownModified = mutableMapOf<FileKey, Long>()
    private val knownProjectFiles = mutableMapOf<FileKey, ProjectFile>()
    // 파일명은 바뀌어도 같은 편집기 composition/session을 유지하기 위한 런타임 정체성이다.
    private val editorSessionKeys = mutableMapOf<FileKey, String>()
    private var nextEditorSessionId = 0L
    private val fileReconciliationMutex = Mutex()
    private val folderFileSelectionMemory = FolderFileSelectionMemory()
    private val navigationGate = LatestNavigationGate()

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
    private val projectCommitService = ProjectCommitService(fileManager)

    private val _commitUiState = MutableStateFlow(CommitUiState())
    val commitUiState: StateFlow<CommitUiState> = _commitUiState.asStateFlow()

    val workspaceSaveError: StateFlow<String?> = workspaceSaveCoordinator.lastErrorMessage
    private val _workspaceTransitionError = MutableStateFlow<String?>(null)
    val workspaceTransitionError: StateFlow<String?> = _workspaceTransitionError.asStateFlow()

    init {
        workspaceSaveCoordinator.register(saveCoordinator::flushAll)

        viewModelScope.launch {
            fileManager.bookmarks.collectLatest { bookmarks ->
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
                    return@collectLatest
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
                val folders = _folderList.value
                val hierarchyContents = loadHierarchyFolderContents(folders)
                _hierarchyFolderContents.value = hierarchyContents
                val currentContent = hierarchyContents[folder.key] ?: return@collect
                val currentKey = _fileList.value.getOrNull(_currentIndex.value)?.key
                _plotFileEntries.value = currentContent.plotEntries
                updateFileList(
                    files = currentContent.files,
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
        launchWorkspaceTransition { fileManager.reset() }
    }

    fun dismissWorkspaceTransitionError() {
        workspaceSaveCoordinator.clearError()
        _workspaceTransitionError.value = null
    }

    fun openCommitDialog() {
        if (_commitUiState.value.isOpen) return
        _commitUiState.value = CommitUiState(isOpen = true, isLoading = true)
        viewModelScope.launch {
            val project = bookmarks.value.projectData
            if (project == null) {
                _commitUiState.value = CommitUiState(
                    isOpen = true,
                    errorMessage = "선택된 프로젝트가 없습니다.",
                )
                return@launch
            }
            runCatching {
                saveCoordinator.flushAll()
                projectCommitService.preview(project) to projectCommitService.history(project)
            }.onSuccess { (preview, history) ->
                _commitUiState.value = CommitUiState(
                    isOpen = true,
                    preview = preview,
                    history = history,
                )
            }.onFailure { error ->
                _commitUiState.value = CommitUiState(
                    isOpen = true,
                    errorMessage = error.message ?: "변경 사항을 확인하지 못했습니다.",
                )
            }
        }
    }

    fun dismissCommitDialog() {
        val state = _commitUiState.value
        if (state.isLoading || state.isCommitting || state.restore?.isRestoring == true) return
        _commitUiState.value = CommitUiState()
    }

    fun openCommitDiff(commitId: String?, change: CommitChange) {
        val state = _commitUiState.value
        if (!state.isOpen || state.isLoading || state.isCommitting) return
        val target = CommitDiffUiState(
            commitId = commitId,
            fileId = change.fileId,
            displayPath = change.displayPath,
            isLoading = true,
        )
        _commitUiState.value = state.copy(diff = target, errorMessage = null)
        viewModelScope.launch {
            val project = bookmarks.value.projectData
            if (project == null) {
                _commitUiState.value = _commitUiState.value.copy(
                    diff = target.copy(isLoading = false, errorMessage = "선택된 프로젝트가 없습니다."),
                )
                return@launch
            }
            runCatching {
                if (commitId == null) saveCoordinator.flushAll()
                projectCommitService.diff(project, commitId, change.fileId)
            }.onSuccess { result ->
                val current = _commitUiState.value
                if (current.diff?.matches(target) == true) {
                    _commitUiState.value = current.copy(
                        diff = target.copy(isLoading = false, result = result),
                    )
                }
            }.onFailure { error ->
                val current = _commitUiState.value
                if (current.diff?.matches(target) == true) {
                    _commitUiState.value = current.copy(
                        diff = target.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "파일 diff를 읽지 못했습니다.",
                        ),
                    )
                }
            }
        }
    }

    fun closeCommitDiff() {
        _commitUiState.value = _commitUiState.value.copy(diff = null)
    }

    fun requestCommitRestore(entry: CommitHistoryEntry) {
        val state = _commitUiState.value
        if (!state.isOpen || state.isLoading || state.isCommitting) return
        _commitUiState.value = state.copy(
            restore = CommitRestoreUiState(entry = entry),
            diff = null,
            errorMessage = null,
        )
    }

    fun dismissCommitRestore() {
        val state = _commitUiState.value
        if (state.restore?.isRestoring == true) return
        _commitUiState.value = state.copy(restore = null)
    }

    fun confirmCommitRestore() {
        val state = _commitUiState.value
        val restore = state.restore ?: return
        if (restore.isRestoring) return
        _commitUiState.value = state.copy(
            restore = restore.copy(isRestoring = true, errorMessage = null),
        )
        viewModelScope.launch {
            val project = bookmarks.value.projectData
            if (project == null) {
                _commitUiState.value = state.copy(
                    restore = restore.copy(
                        errorMessage = "선택된 프로젝트가 없습니다.",
                    ),
                )
                return@launch
            }
            val preferredFileKey = _fileList.value.getOrNull(_currentIndex.value)?.key
            val preferredFolderKey = _currentFolder.value?.key
            val restoreResult = runCatching {
                saveCoordinator.flushAll()
                projectCommitService.restore(project, restore.entry.commit.id)
            }
            val restoreError = restoreResult.exceptionOrNull()
            if (restoreError != null) {
                val current = _commitUiState.value
                _commitUiState.value = current.copy(
                    restore = restore.copy(
                        isRestoring = false,
                        errorMessage = restoreError.message ?: "선택한 커밋을 복구하지 못했습니다.",
                    ),
                )
                return@launch
            }

            clearProjectState()
            runCatching {
                refreshFoldersAndFiles(
                    project = project,
                    preferredKey = preferredFileKey,
                    preferredFolderKey = preferredFolderKey,
                    cancelRemoved = false,
                )
                _fileList.value.getOrNull(_currentIndex.value)?.let { selected ->
                    val noteFile = fileManager.readMarkdown(selected.platformFile)
                    putLoadedNote(selected.key, noteFile)
                    fileManager.lastModified(selected.platformFile)?.let { modified ->
                        knownModified[selected.key] = modified
                    }
                }
                val preview = projectCommitService.preview(project)
                val history = projectCommitService.history(project)
                preview to history
            }.onSuccess { (preview, history) ->
                _commitUiState.value = CommitUiState(
                    isOpen = true,
                    preview = preview,
                    history = history,
                )
            }.onFailure { error ->
                _commitUiState.value = CommitUiState(
                    isOpen = true,
                    errorMessage = "복구는 완료했지만 화면을 새로고침하지 못했습니다. ${error.message.orEmpty()}",
                )
            }
        }
    }

    fun createCommit(message: String) {
        val state = _commitUiState.value
        val preview = state.preview ?: return
        if (state.isCommitting || !preview.hasChanges || message.isBlank()) return
        _commitUiState.value = state.copy(isCommitting = true, errorMessage = null)
        viewModelScope.launch {
            val project = bookmarks.value.projectData
            if (project == null) {
                _commitUiState.value = state.copy(
                    isCommitting = false,
                    errorMessage = "선택된 프로젝트가 없습니다.",
                )
                return@launch
            }
            runCatching {
                // 미리보기 이후 발생한 마지막 입력도 포함하고 service에서 tree를 다시 계산한다.
                saveCoordinator.flushAll()
                projectCommitService.commit(project, message)
            }.onSuccess {
                _commitUiState.value = CommitUiState()
            }.onFailure { error ->
                _commitUiState.value = state.copy(
                    isCommitting = false,
                    errorMessage = error.message ?: "커밋을 만들지 못했습니다.",
                )
            }
        }
    }

    fun refreshProjectList() {
        viewModelScope.launch {
            val vault = bookmarks.value.vaultData ?: return@launch
            _projectList.value = fileManager.listProject(vault)
        }
    }

    fun selectProject(project: PlatformFile) {
        launchWorkspaceTransition { fileManager.pickProject(project) }
    }

    fun renameCurrentProject(name: String) {
        viewModelScope.launch {
            val project = bookmarks.value.projectData ?: return@launch
            _projectRenameState.value = ProjectRenameUiState(isRenaming = true)
            val renamed = runCatching {
                saveCoordinator.flushAll()
                fileManager.renameProject(project, name)
            }.getOrNull()
            if (renamed == null) {
                _projectRenameState.value = ProjectRenameUiState(
                    errorMessage = "프로젝트 이름을 변경하지 못했습니다. 이름과 Vault 접근 권한을 확인해 주세요.",
                )
                return@launch
            }

            bookmarks.value.vaultData?.let { vault ->
                runCatching { fileManager.listProject(vault) }
                    .onSuccess { projects -> _projectList.value = projects }
            }
            _projectRenameState.value = ProjectRenameUiState(completedName = renamed.name)
        }
    }

    fun consumeProjectRenameResult() {
        _projectRenameState.value = ProjectRenameUiState()
    }

    fun navigateToProjectRoot() {
        selectFolder(FolderKey.Base)
    }

    fun selectFolder(folderKey: FolderKey) {
        launchNavigation { isLatest ->
            val folder = _folderList.value.find { it.key == folderKey } ?: return@launchNavigation
            val content = loadFolderContent(folder)
            if (!isLatest()) return@launchNavigation
            val preferredKey = folderFileSelectionMemory.preferred(
                folderKey = folder.key,
                availableKeys = content.files.map(ProjectFile::key),
            )
            _currentFolder.value = folder
            _hierarchyFolderContents.value += folder.key to content
            _plotFileEntries.value = content.plotEntries
            updateFileList(content.files, preferredKey = preferredKey, cancelRemoved = false)
            content.files.getOrNull(_currentIndex.value)?.let { selected ->
                fileManager.pickFile(selected)
            }
        }
    }

    fun selectFile(fileKey: FileKey) {
        launchNavigation { isLatest ->
            val folder = _folderList.value.find { it.key == fileKey.folder }
                ?: return@launchNavigation
            val content = _hierarchyFolderContents.value[folder.key]
                ?.takeIf { cached -> cached.files.any { it.key == fileKey } }
                ?: loadFolderContent(folder)
            val file = content.files.find { it.key == fileKey } ?: return@launchNavigation
            fileManager.pickFile(file)
            if (!isLatest()) return@launchNavigation
            _currentFolder.value = folder
            _hierarchyFolderContents.value += folder.key to content
            _plotFileEntries.value = content.plotEntries
            updateFileList(content.files, preferredKey = fileKey, cancelRemoved = false)
            folderFileSelectionMemory.remember(file.key)
        }
    }

    internal fun editorSessionKey(fileKey: FileKey): String =
        editorSessionKeys.getOrPut(fileKey) { "editor-${nextEditorSessionId++}" }

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
            putLoadedNote(created.key, noteFile)
            val freshFiles = sortedFiles(folder)
            updateFileList(freshFiles, preferredKey = created.key, cancelRemoved = false)
            fileManager.pickFile(created)
        }
    }

    suspend fun saveDefaultOrder(
        folderKey: FolderKey,
        orderedFileKeys: List<FileKey>,
    ): Boolean = fileReconciliationMutex.withLock {
        val folder = _folderList.value.find { it.key == folderKey } ?: return@withLock false
        val config = folderConfig(folderKey)
        if (config.type != FolderType.DEFAULT || config.isPlot) return@withLock false

        try {
            saveCoordinator.flush(orderedFileKeys.toSet())
            val selectedOldKey = selectedKeyFor(folderKey)
            val updates = fileManager.applyDefaultOrder(folder, orderedFileKeys)
                ?: return@withLock false
            reconcileOrderUpdates(
                folder = folder,
                updates = updates.map { update ->
                    OrderStateUpdate(
                        oldKey = update.oldKey,
                        projectFile = update.projectFile,
                    )
                },
                selectedOldKey = selectedOldKey,
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    suspend fun savePlotOrder(
        folderKey: FolderKey,
        assignments: List<PlotOrderAssignment>,
    ): Boolean = fileReconciliationMutex.withLock {
        val folder = _folderList.value.find { it.key == folderKey } ?: return@withLock false
        if (!folderConfig(folderKey).isPlot) return@withLock false

        try {
            val assignmentKeys = assignments.mapTo(mutableSetOf()) { it.fileKey }
            saveCoordinator.flush(assignmentKeys)
            val selectedOldKey = selectedKeyFor(folderKey)
            val updates = fileManager.applyPlotOrder(folder, assignments)
                ?: return@withLock false
            reconcileOrderUpdates(
                folder = folder,
                updates = updates.map { update ->
                    OrderStateUpdate(
                        oldKey = update.oldKey,
                        projectFile = update.projectFile,
                        noteFile = update.noteFile,
                    )
                },
                selectedOldKey = selectedOldKey,
            )
            true
        } catch (error: CancellationException) {
            throw error
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

    fun requestDeleteDirectory(folderKey: FolderKey) {
        viewModelScope.launch {
            _pendingFolderDeletion.value = fileManager.inspectProjectFolderDeletion(folderKey)
        }
    }

    fun dismissDeleteDirectory() {
        _pendingFolderDeletion.value = null
    }

    fun confirmDeleteDirectory() {
        viewModelScope.launch {
            val preview = _pendingFolderDeletion.value ?: return@launch
            if (!preview.canDelete) return@launch
            val deletedKeys = preview.markdownFiles.mapTo(mutableSetOf(), ProjectFile::key)
            saveCoordinator.flush(deletedKeys)
            val result = fileManager.deleteProjectFolder(preview.folder.key) ?: return@launch
            folderFileSelectionMemory.forget(result.folderKey)

            result.deletedFileKeys.forEach { key ->
                saveCoordinator.cancel(key)
                knownProjectFiles.remove(key)
                knownModified.remove(key)
            }
            _fileLoadStates.value = _fileLoadStates.value - result.deletedFileKeys.toSet()
            _pendingFolderDeletion.value = null

            val project = bookmarks.value.projectData ?: return@launch
            val previousFolderKey = _currentFolder.value?.key
            refreshFoldersAndFiles(
                project = project,
                preferredKey = null,
                cancelRemoved = true,
                preferredFolderKey = previousFolderKey
                    ?.takeUnless { it == result.folderKey }
                    ?: FolderKey.Base,
            )
        }
    }

    /**
     * 열려있는(캐시된) 파일들의 외부 변경을 감지하여 자동 리로드한다.
     * - 파일 목록도 함께 갱신 (외부에서 파일 추가/삭제)
     * - mtime 이 마지막 인지 시각과 같으면 skip (자기 쓰기 포함)
     * - 내용이 실제로 다르면 캐시 교체 → EditorPage 의 value 가 바뀌어 에디터가 재파싱 (외부 우선)
     */
    private suspend fun checkExternalChanges() {
        fileReconciliationMutex.withLock {
            val project = fileManager.bookmarks.value.projectData ?: return@withLock

            // 1. 폴더·파일 목록 갱신 (외부 추가/삭제 반영, 현재 FileKey 보존)
            refreshFoldersAndFiles(project, preferredKey = null, cancelRemoved = true)

            // 2. 캐시된 파일들의 내용 변경 감지
            for (projectFile in _fileList.value) {
                val key = projectFile.key
                val file = projectFile.platformFile
                val cached = loadedNote(key) ?: continue // 아직 안 연 파일은 skip
                val diskModified = fileManager.lastModified(file) ?: continue
                if (knownModified[key] == diskModified) continue // 변화 없음 (자기 쓰기 포함)

                // external wins: 외부 mtime 변경을 보면 이 파일의 stale pending write를 먼저 취소.
                saveCoordinator.cancel(key)
                val fresh = fileManager.readMarkdown(file)
                knownModified[key] = fileManager.lastModified(file) ?: diskModified
                // mtime 은 달라졌지만 내용은 동일할 수 있음(자기 쓰기 레이스 등) → 실제 diff 일 때만 교체
                if (fresh.inject() != cached.inject()) {
                    putLoadedNote(key, fresh)
                }
            }
        }
    }

    fun onPageChanged(index: Int) {
        launchNavigation { isLatest ->
            val file = _fileList.value.getOrNull(index) ?: return@launchNavigation
            fileManager.pickFile(file)
            if (!isLatest()) return@launchNavigation
            _currentIndex.value = index
            folderFileSelectionMemory.remember(file.key)
        }
    }

    fun loadPage(projectFile: ProjectFile) {
        loadPage(projectFile, force = false)
    }

    fun retryPage(projectFile: ProjectFile) {
        loadPage(projectFile, force = true)
    }

    private fun loadPage(projectFile: ProjectFile, force: Boolean) {
        viewModelScope.launch {
            val key = projectFile.key
            val file = projectFile.platformFile
            val projectLocation = bookmarks.value.projectData?.toString()
            knownProjectFiles[key] = projectFile
            val currentState = _fileLoadStates.value[key]
            if (!force && (currentState is FileLoadUiState.Loading || currentState is FileLoadUiState.Loaded)) {
                return@launch
            }
            setFileLoadState(key, FileLoadUiState.Loading)

            runCatching { fileManager.readMarkdown(file) }
                .onSuccess { markdown ->
                    if (!isCurrentProjectFile(projectFile, projectLocation)) return@onSuccess
                    putLoadedNote(key, markdown)
                    fileManager.lastModified(file)?.let { knownModified[key] = it }
                }
                .onFailure { error ->
                    if (!isCurrentProjectFile(projectFile, projectLocation)) return@onFailure
                    setFileLoadState(
                        key,
                        FileLoadUiState.Error(error.message ?: "파일을 읽지 못했습니다."),
                    )
                }
        }
    }

    fun updateBody(fileKey: FileKey, newBody: String) {
        val current = loadedNote(fileKey) ?: return
        if (current.body == newBody) return
        val updated = current.withBody(newBody)
        putLoadedNote(fileKey, updated)
        val projectFile = knownProjectFiles[fileKey] ?: return
        saveCoordinator.schedule(fileKey, PendingWrite(projectFile, updated))
    }

    fun updateDirectory(folderKey: FolderKey, updatedName: String, folderConfig: FolderConfig) {
        viewModelScope.launch {
            val project = bookmarks.value.projectData ?: return@launch
            val previousConfig = projectConfig.value ?: return@launch
            val result = folderSettingsService.update(
                project = project,
                previousConfig = previousConfig,
                folderKey = folderKey,
                updatedName = updatedName,
                folderConfig = folderConfig,
            ) ?: return@launch
            val updates = result.autoTagUpdates

            if (result.folderKey != folderKey) {
                folderFileSelectionMemory.renameFolder(folderKey, result.folderKey)
            }

            if (updates.isNotEmpty()) {
                _fileLoadStates.value += updates.associate {
                    it.projectFile.key to FileLoadUiState.Loaded(it.noteFile)
                }
                updates.forEach { update ->
                    val key = update.projectFile.key
                    knownProjectFiles[key] = update.projectFile
                    fileManager.lastModified(update.projectFile.platformFile)?.let { modified ->
                        knownModified[key] = modified
                    }
                }
            }
            refreshFoldersAndFiles(
                project = project,
                preferredKey = result.selectedFileKey,
                cancelRemoved = result.folderKey != folderKey,
                preferredFolderKey = result.folderKey,
            )
        }
    }

    suspend fun renameFile(projectFile: ProjectFile, newName: String): String? =
        fileReconciliationMutex.withLock {
            val file = projectFile.platformFile
            if (file.nameWithoutExtension == newName) return@withLock null
            projectFileTitleError(newName)?.let { return@withLock it }
            val targetFileName = "$newName.md"
            if (_fileList.value.any { candidate ->
                    candidate.key != projectFile.key &&
                        candidate.key.fileName.equals(targetFileName, ignoreCase = true)
                }
            ) {
                return@withLock "같은 이름의 파일이 이미 있습니다."
            }

            val oldKey = projectFile.key
            saveCoordinator.cancel(oldKey)
            val renamed = runCatching { fileManager.renameFile(projectFile, newName) }.getOrNull()
            if (renamed == null) {
                loadedNote(oldKey)?.let {
                    saveCoordinator.schedule(oldKey, PendingWrite(projectFile, it))
                }
                return@withLock "파일 이름을 변경하지 못했습니다. 이름과 폴더 접근 권한을 확인해 주세요."
            }
            val newKey = oldKey.rename(renamed.name)
            folderFileSelectionMemory.renameFile(oldKey, newKey)
            moveEditorSessionKey(oldKey, newKey)
            val renamedProjectFile = ProjectFile(newKey, renamed)
            knownProjectFiles.remove(oldKey)
            knownProjectFiles[newKey] = renamedProjectFile
            // 캐시 key 교체 (Markdown 인스턴스는 그대로 유지)
            val cached = loadedNote(oldKey)
            saveCoordinator.cancel(oldKey)
            if (cached != null) {
                _fileLoadStates.value = _fileLoadStates.value
                    .toMutableMap()
                    .also {
                        it.remove(oldKey)
                        it[newKey] = FileLoadUiState.Loaded(cached)
                    }
            }
            // mtime 추적 key 도 교체 (stale 엔트리 방지)
            knownModified.remove(oldKey)
            fileManager.lastModified(renamed)?.let { knownModified[newKey] = it }
            if (cached != null) {
                saveCoordinator.schedule(newKey, PendingWrite(renamedProjectFile, cached))
            }

            // 외부 변경 polling을 기다리지 않고 현재 목록과 선택을 즉시 새 key로 교체한다.
            // 캐시와 editor session도 이미 이동했으므로 편집기를 다시 만들거나 파일을 재읽지 않는다.
            val selectedKey = _fileList.value.getOrNull(_currentIndex.value)?.key
            val replacedFiles = _fileList.value.map { candidate ->
                if (candidate.key == oldKey) renamedProjectFile else candidate
            }
            val config = folderConfig(oldKey.folder)
            val orderedFiles = if (config.isPlot && _plotFileEntries.value.isNotEmpty()) {
                _plotFileEntries.value
                    .map { entry ->
                        if (entry.projectFile.key == oldKey) {
                            entry.copy(projectFile = renamedProjectFile)
                        } else {
                            entry
                        }
                    }
                    .sortedForPlot()
                    .also { _plotFileEntries.value = it }
                    .map(PlotFileEntry::projectFile)
            } else {
                replacedFiles.sortedFor(config)
            }
            updateFileList(
                files = orderedFiles,
                preferredKey = if (selectedKey == oldKey) newKey else selectedKey,
                cancelRemoved = false,
            )
            runCatching { fileManager.pickFile(renamedProjectFile) }
            null
        }

    private suspend fun refreshFoldersAndFiles(
        project: PlatformFile,
        preferredKey: FileKey?,
        cancelRemoved: Boolean,
        preferredFolderKey: FolderKey? = null,
    ) {
        val previousFolderKey = _currentFolder.value?.key
        val folders = fileManager.listFolders(project)
        _folderList.value = folders
        val previousHierarchyKeys = _hierarchyFolderContents.value.values
            .flatMapTo(mutableSetOf()) { content -> content.files.map(ProjectFile::key) }
        val hierarchyContents = loadHierarchyFolderContents(folders)
        _hierarchyFolderContents.value = hierarchyContents
        if (cancelRemoved) {
            val freshHierarchyKeys = hierarchyContents.values
                .flatMapTo(mutableSetOf()) { content -> content.files.map(ProjectFile::key) }
            val removedKeys = previousHierarchyKeys - freshHierarchyKeys
            removedKeys.forEach { key ->
                saveCoordinator.cancel(key)
                knownProjectFiles.remove(key)
                knownModified.remove(key)
                editorSessionKeys.remove(key)
            }
            if (removedKeys.isNotEmpty()) {
                _fileLoadStates.value = _fileLoadStates.value - removedKeys
            }
        }

        val requestedFolderKey = preferredKey?.folder
            ?: preferredFolderKey
            ?: previousFolderKey
            ?: FolderKey.Base
        val folder = folders.find { it.key == requestedFolderKey }
            ?: folders.find { it.key == FolderKey.Base }
            ?: return
        _currentFolder.value = folder

        val content = hierarchyContents[folder.key] ?: HierarchyFolderContent()
        _plotFileEntries.value = content.plotEntries
        val rememberedKey = folderFileSelectionMemory.preferred(
            folderKey = folder.key,
            availableKeys = content.files.map(ProjectFile::key),
        )
        updateFileList(
            files = content.files,
            preferredKey = preferredKey ?: rememberedKey,
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
                editorSessionKeys.remove(key)
            }
            if (removedKeys.isNotEmpty()) {
                _fileLoadStates.value = _fileLoadStates.value - removedKeys
            }
        }

        files.forEach {
            knownProjectFiles[it.key] = it
            editorSessionKey(it.key)
        }
        _currentFolder.value?.key?.let { folderKey ->
            _hierarchyFolderContents.value += folderKey to HierarchyFolderContent(
                files = files,
                plotEntries = _plotFileEntries.value,
            )
        }
        val currentKey = preferredKey ?: _fileList.value.getOrNull(_currentIndex.value)?.key
        _fileList.value = files
        _currentIndex.value = currentKey
            ?.let { key -> files.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
            ?: 0
        files.getOrNull(_currentIndex.value)?.let { selected ->
            folderFileSelectionMemory.remember(selected.key)
        }
    }

    private fun selectedKeyFor(folderKey: FolderKey): FileKey? {
        if (_currentFolder.value?.key == folderKey) {
            return _fileList.value.getOrNull(_currentIndex.value)?.key
        }
        val availableKeys = _hierarchyFolderContents.value[folderKey]
            ?.files
            ?.map(ProjectFile::key)
            .orEmpty()
        return folderFileSelectionMemory.preferred(folderKey, availableKeys)
    }

    private suspend fun reconcileOrderUpdates(
        folder: ProjectFolder,
        updates: List<OrderStateUpdate>,
        selectedOldKey: FileKey?,
    ) {
        val keyChanges = updates.associate { it.oldKey to it.projectFile.key }
        val oldKeys = keyChanges.keys
        val selectedNewKey = selectedOldKey?.let { keyChanges[it] ?: it }
        if (selectedNewKey != null) {
            folderFileSelectionMemory.remember(selectedNewKey)
        }

        // 순서 교환은 A→B, B→A cycle을 만들 수 있다. 모든 old 상태를 먼저 snapshot/remove한 뒤
        // new key로 넣어야 순차 이동 중 앞서 기록한 상태를 뒤 항목이 덮어쓰지 않는다.
        val editorSessions = oldKeys.mapNotNull { oldKey ->
            editorSessionKeys[oldKey]?.let { session -> oldKey to session }
        }.toMap()
        oldKeys.forEach { oldKey -> editorSessionKeys.remove(oldKey) }
        updates.forEach { update ->
            editorSessions[update.oldKey]?.let { session ->
                editorSessionKeys[update.projectFile.key] = session
            }
        }

        val previousLoadStates = _fileLoadStates.value.filterKeys { it in oldKeys }
        _fileLoadStates.value = _fileLoadStates.value.toMutableMap().apply {
            oldKeys.forEach { oldKey -> remove(oldKey) }
            updates.forEach { update ->
                val nextState = update.noteFile
                    ?.let(FileLoadUiState::Loaded)
                    ?: previousLoadStates[update.oldKey]
                if (nextState != null) this[update.projectFile.key] = nextState
            }
        }
        oldKeys.forEach { oldKey ->
            saveCoordinator.cancel(oldKey)
            knownProjectFiles.remove(oldKey)
            knownModified.remove(oldKey)
        }
        updates.forEach { update ->
            knownProjectFiles[update.projectFile.key] = update.projectFile
            fileManager.lastModified(update.projectFile.platformFile)?.let { modified ->
                knownModified[update.projectFile.key] = modified
            }
        }

        val content = loadFolderContent(folder)
        _hierarchyFolderContents.value += folder.key to content
        if (_currentFolder.value?.key != folder.key) return

        _plotFileEntries.value = content.plotEntries
        updateFileList(
            files = content.files,
            preferredKey = selectedNewKey,
            cancelRemoved = false,
        )
    }

    private fun clearProjectState() {
        saveCoordinator.cancelAll()
        _folderList.value = emptyList()
        _currentFolder.value = null
        _fileList.value = emptyList()
        _plotFileEntries.value = emptyList()
        _hierarchyFolderContents.value = emptyMap()
        _pendingFolderDeletion.value = null
        _currentIndex.value = 0
        _fileLoadStates.value = emptyMap()
        knownModified.clear()
        knownProjectFiles.clear()
        editorSessionKeys.clear()
        folderFileSelectionMemory.clear()
        _commitUiState.value = CommitUiState()
    }

    private suspend fun sortedFiles(folder: ProjectFolder): List<ProjectFile> {
        val content = loadFolderContent(folder)
        _hierarchyFolderContents.value += folder.key to content
        _plotFileEntries.value = content.plotEntries
        return content.files
    }

    private suspend fun loadHierarchyFolderContents(
        folders: List<ProjectFolder>,
    ): Map<FolderKey, HierarchyFolderContent> = folders.associate { folder ->
        folder.key to loadFolderContent(folder)
    }

    private suspend fun loadFolderContent(folder: ProjectFolder): HierarchyFolderContent {
        val config = folderConfig(folder.key)
        val files = fileManager.listProjectFiles(folder)
        if (!config.isPlot) {
            return HierarchyFolderContent(files = files.sortedFor(config))
        }
        val plotEntries = loadPlotEntries(folder, files).sortedForPlot()
        return HierarchyFolderContent(
            files = plotEntries.map(PlotFileEntry::projectFile),
            plotEntries = plotEntries,
        )
    }

    private suspend fun loadPlotEntries(
        folder: ProjectFolder,
        files: List<ProjectFile>? = null,
    ): List<PlotFileEntry> = (files ?: fileManager.listProjectFiles(folder)).map { projectFile ->
        val noteFile = loadedNote(projectFile.key)
            ?: runCatching { fileManager.readMarkdown(projectFile.platformFile) }
                .onSuccess { loaded -> putLoadedNote(projectFile.key, loaded) }
                .onFailure { error ->
                    setFileLoadState(
                        projectFile.key,
                        FileLoadUiState.Error(error.message ?: "파일을 읽지 못했습니다."),
                    )
                }
                .getOrNull()
        PlotFileEntry(
            projectFile = projectFile,
            stage = noteFile?.plotStage,
            order = projectFile.plotOrder(),
        )
    }

    private fun loadedNote(fileKey: FileKey): NoteFile? =
        (_fileLoadStates.value[fileKey] as? FileLoadUiState.Loaded)?.noteFile

    private fun putLoadedNote(fileKey: FileKey, noteFile: NoteFile) {
        setFileLoadState(fileKey, FileLoadUiState.Loaded(noteFile))
    }

    private fun setFileLoadState(fileKey: FileKey, state: FileLoadUiState) {
        _fileLoadStates.value += fileKey to state
    }

    private fun moveEditorSessionKey(oldKey: FileKey, newKey: FileKey) {
        if (oldKey == newKey) return
        val sessionKey = editorSessionKeys.remove(oldKey) ?: return
        editorSessionKeys[newKey] = sessionKey
    }

    private fun isCurrentProjectFile(projectFile: ProjectFile, projectLocation: String?): Boolean {
        if (activeProjectLocation != projectLocation) return false
        return knownProjectFiles[projectFile.key]?.platformFile?.toString() ==
            projectFile.platformFile.toString()
    }

    private fun folderConfig(folderKey: FolderKey): FolderConfig =
        projectConfig.value?.folders?.get(folderKey.relativePath)
            ?: if (folderKey == FolderKey.Base) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()

    private fun autoTagsFor(folderKey: FolderKey): List<String> {
        return projectConfig.value?.effectiveAutoTags(folderKey.relativePath).orEmpty()
    }

    private fun launchWorkspaceTransition(action: suspend () -> Unit) {
        launchNavigation { isLatest ->
            workspaceSaveCoordinator.runAfterFlush {
                if (isLatest()) action()
            }
                .onFailure { error ->
                    if (isLatest() && workspaceSaveCoordinator.lastErrorMessage.value == null) {
                        _workspaceTransitionError.value =
                            error.message ?: "작업 공간을 전환하지 못했습니다."
                    }
                }
        }
    }

    private fun launchNavigation(action: suspend (isLatest: () -> Boolean) -> Unit) {
        val request = navigationGate.newRequest()
        viewModelScope.launch {
            // 파일 선택 bookmark와 순서 변경 rename이 서로의 오래된 경로를 덮어쓰지 않도록
            // 탐색도 외부 변경·rename과 같은 임계 구역에서 직렬화한다.
            fileReconciliationMutex.withLock {
                navigationGate.run(request, action)
            }
        }
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

private data class OrderStateUpdate(
    val oldKey: FileKey,
    val projectFile: ProjectFile,
    val noteFile: NoteFile? = null,
)

data class ProjectRenameUiState(
    val isRenaming: Boolean = false,
    val errorMessage: String? = null,
    val completedName: String? = null,
)

data class HierarchyFolderContent(
    val files: List<ProjectFile> = emptyList(),
    val plotEntries: List<PlotFileEntry> = emptyList(),
)

sealed interface FileLoadUiState {
    data object Loading : FileLoadUiState
    data class Loaded(val noteFile: NoteFile) : FileLoadUiState
    data class Error(val message: String) : FileLoadUiState
}

data class CommitUiState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val isCommitting: Boolean = false,
    val preview: CommitPreview? = null,
    val history: List<CommitHistoryEntry> = emptyList(),
    val diff: CommitDiffUiState? = null,
    val restore: CommitRestoreUiState? = null,
    val errorMessage: String? = null,
)

data class CommitDiffUiState(
    val commitId: String?,
    val fileId: String,
    val displayPath: String,
    val isLoading: Boolean = false,
    val result: FileLineDiff? = null,
    val errorMessage: String? = null,
) {
    fun matches(other: CommitDiffUiState): Boolean =
        commitId == other.commitId && fileId == other.fileId
}

data class CommitRestoreUiState(
    val entry: CommitHistoryEntry,
    val isRestoring: Boolean = false,
    val errorMessage: String? = null,
)
