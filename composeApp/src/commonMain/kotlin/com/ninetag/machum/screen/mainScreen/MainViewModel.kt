package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.screen.mainScreen.leftSideMenu.FolderPresentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninetag.machum.commit.CommitPreview
import com.ninetag.machum.commit.CommitHistoryEntry
import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.CommitFileSide
import com.ninetag.machum.commit.FileRestoreResult
import com.ninetag.machum.commit.FileLineDiff
import com.ninetag.machum.commit.ProjectCommitService
import com.ninetag.machum.commit.RestoreSessionStaleException
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.effectiveAutoTags
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.FileCreationIncompleteException
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.GeneralSourceState
import com.ninetag.machum.external.GeneralSourcePlan
import com.ninetag.machum.external.DocumentPropertyProtectionPolicy
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
import com.ninetag.machum.external.withManagedTagChanges
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class MainViewModel internal constructor(
    private val fileManager: FileManager,
    private val workspaceSaveCoordinator: WorkspaceSaveCoordinator,
    private val writeNote: suspend (PlatformFile, NoteFile) -> Unit,
) : ViewModel() {
    constructor(fileManager: FileManager, workspaceSaveCoordinator: WorkspaceSaveCoordinator) :
        this(fileManager, workspaceSaveCoordinator, fileManager::writeMarkdown)

    val bookmarks: StateFlow<Bookmarks> = fileManager.bookmarks
    val projectConfig: StateFlow<ProjectConfig?> = fileManager.projectConfig

    private val _projectList = MutableStateFlow<List<PlatformFile>>(emptyList())
    val projectList: StateFlow<List<PlatformFile>> = _projectList.asStateFlow()

    private val _generalFolderList = MutableStateFlow<List<PlatformFile>>(emptyList())
    val generalFolderList: StateFlow<List<PlatformFile>> = _generalFolderList.asStateFlow()

    private val _hierarchyState = MutableStateFlow(HierarchyUiState())
    val hierarchyState: StateFlow<HierarchyUiState> = _hierarchyState.asStateFlow()
    private val _generalSourceState = MutableStateFlow<GeneralSourceState?>(null)
    val generalSourceState = _generalSourceState.asStateFlow()
    private val _documentConflicts = MutableStateFlow<Map<FileKey, String>>(emptyMap())
    val documentConflicts = _documentConflicts.asStateFlow()

    private val _pendingFolderDeletion = MutableStateFlow<ProjectFolderDeletionPreview?>(null)
    val pendingFolderDeletion: StateFlow<ProjectFolderDeletionPreview?> =
        _pendingFolderDeletion.asStateFlow()
    private val _pendingFileTrash = MutableStateFlow<FileTrashUiState?>(null)
    val pendingFileTrash: StateFlow<FileTrashUiState?> = _pendingFileTrash.asStateFlow()
    private var fileTrashPreparationInProgress = false


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
    private val pageLoadJobs = mutableMapOf<FileKey, Job>()

    private var activeProjectLocation: String? = null
    private var activeWorkspaceKind: WorkspaceKind? = null
    private var activeVaultLocation: String? = null

    // 앱/창 활성 상태 — 활성일 때만 폴링 (Phase 2) + 활성 전환 시 즉시 1회 검사 (Phase 1)
    private val _active = MutableStateFlow(false)

    private val saveCoordinator = DebouncedSaveCoordinator<FileKey, PendingWrite>(
        scope = viewModelScope,
        debounceMillis = SAVE_DEBOUNCE_MS,
        onSaveFailure = { fileKey, error ->
            workspaceSaveCoordinator.reportAutoSaveFailure(fileKey.relativePath, error)
        },
    ) { fileKey, pendingWrite ->
        check(fileKey !in _documentConflicts.value) { _documentConflicts.value[fileKey].orEmpty() }
        val file = pendingWrite.projectFile.platformFile
        writeNote(file, pendingWrite.noteFile)
        // 자기 쓰기 mtime 기록 → 폴링이 외부 변경으로 오인하지 않도록
        fileManager.lastModified(file)?.let { knownModified[fileKey] = it }
    }

    private val folderSettingsService = FolderSettingsService(fileManager)
    private val projectCommitService = ProjectCommitService(fileManager)

    private val _commitCreateUiState = MutableStateFlow(CommitCreateUiState())
    val commitCreateUiState: StateFlow<CommitCreateUiState> = _commitCreateUiState.asStateFlow()

    private val _commitHistoryUiState = MutableStateFlow(CommitHistoryUiState())
    val commitHistoryUiState: StateFlow<CommitHistoryUiState> = _commitHistoryUiState.asStateFlow()
    private var commitReadJob: Job? = null
    private var commitRequestGeneration = 0L

    private val _projectBaselineUiState =
        MutableStateFlow<ProjectBaselineUiState>(ProjectBaselineUiState.Idle)
    val projectBaselineUiState: StateFlow<ProjectBaselineUiState> =
        _projectBaselineUiState.asStateFlow()
    private var projectBaselineJob: Job? = null

    val workspaceSaveError: StateFlow<String?> = workspaceSaveCoordinator.lastErrorMessage
    private val _workspaceTransitionError = MutableStateFlow<String?>(null)
    val workspaceTransitionError: StateFlow<String?> = _workspaceTransitionError.asStateFlow()

    private val _workspaceSelectionVisible = MutableStateFlow(false)
    val workspaceSelectionVisible: StateFlow<Boolean> = _workspaceSelectionVisible.asStateFlow()
    private val _vaultSelectionVisible = MutableStateFlow(false)
    val vaultSelectionVisible: StateFlow<Boolean> = _vaultSelectionVisible.asStateFlow()
    private val _workspaceSelectionPending = MutableStateFlow(false)
    val workspaceSelectionPending: StateFlow<Boolean> = _workspaceSelectionPending.asStateFlow()
    private var openingWorkspaceSelection: Boolean
        get() = _workspaceSelectionPending.value
        set(value) { _workspaceSelectionPending.value = value }
    private var workspaceUiGeneration = 0L
    private val workspaceInputBlocked: Boolean
        get() = openingWorkspaceSelection || _workspaceSelectionVisible.value

    private fun workspaceOperationBusy(): Boolean =
        _projectBaselineUiState.value is ProjectBaselineUiState.Preparing ||
            _commitCreateUiState.value.isCommitting ||
            _commitHistoryUiState.value.restore?.isRestoring == true

    /** Leave editing only after writes settle; bookmarks remain ordinary reopening data. */
    fun openWorkspaceSelection() {
        if (workspaceInputBlocked || workspaceOperationBusy()) return
        val vault = bookmarks.value.vaultData?.toString() ?: return
        openingWorkspaceSelection = true
        val generation = workspaceUiGeneration
        navigationGate.newRequest()
        viewModelScope.launch {
            try {
                fileReconciliationMutex.withLock {
                    workspaceSaveCoordinator.runAfterFlush {
                        check(generation == workspaceUiGeneration && bookmarks.value.vaultData?.toString() == vault) {
                            "작업 공간이 변경되었습니다."
                        }
                        check(!workspaceOperationBusy()) { "진행 중인 작업이 끝난 뒤 다시 시도해 주세요." }
                        showWorkspaceSelection()
                    }.onFailure { error ->
                        if (workspaceSaveCoordinator.lastErrorMessage.value == null) {
                            _workspaceTransitionError.value = error.message
                        }
                    }
                }
            } finally {
                openingWorkspaceSelection = false
            }
        }
    }

    /** Called under the save fence: no pending editor write is discarded. */
    private fun showWorkspaceSelection() {
        workspaceUiGeneration++
        clearProjectState()
        activeProjectLocation = null
        activeWorkspaceKind = null
        _vaultSelectionVisible.value = false
        _workspaceSelectionVisible.value = true
    }

    /** Workspace selection always goes up to Vault selection, never to the old editor. */
    fun openVaultSelection() {
        if (openingWorkspaceSelection || workspaceOperationBusy() ||
            fileManager.workspaceOpenRequest.value != null ||
            (!_workspaceSelectionVisible.value && bookmarks.value.projectData != null)) return
        workspaceUiGeneration++
        navigationGate.newRequest()
        _workspaceSelectionVisible.value = true
        _vaultSelectionVisible.value = true
    }

    /** Called only by a successful native selection or new-Vault creation. */
    fun completeVaultSelection() {
        if (bookmarks.value.vaultData == null) return
        workspaceUiGeneration++
        _vaultSelectionVisible.value = false
        _workspaceSelectionVisible.value = true
    }

    /** Call only after the selected directory has opened successfully, including setup confirmation. */
    suspend fun completeWorkspaceSelection() {
        val snapshot = bookmarks.value
        val project = snapshot.projectData ?: return
        if (fileManager.workspaceOpenRequest.value != null) return
        activeProjectLocation = project.toString()
        activeWorkspaceKind = snapshot.workspaceKind
        val preferred = snapshot.fileRelativePath?.let { runCatching { FileKey.of(it) }.getOrNull() }
        refreshFoldersAndFiles(project, preferred, cancelRemoved = false)
        navigationGate.newRequest()
        _vaultSelectionVisible.value = false
        _workspaceSelectionVisible.value = false
    }

    /** Selection commands share the save/rename fence; captured actions cannot run in a different Vault. */
    suspend fun <T> runWorkspaceSelectionAction(action: suspend () -> T): Result<T> {
        val vault = bookmarks.value.vaultData?.toString()
        val generation = workspaceUiGeneration
        return fileReconciliationMutex.withLock {
            workspaceSaveCoordinator.runAfterFlush {
                check(!openingWorkspaceSelection && !workspaceOperationBusy()) {
                    "진행 중인 작업이 끝난 뒤 다시 시도해 주세요."
                }
                check(generation == workspaceUiGeneration && vault == bookmarks.value.vaultData?.toString()) {
                    "작업 공간이 변경되었습니다."
                }
                check(!_vaultSelectionVisible.value && (_workspaceSelectionVisible.value || bookmarks.value.projectData == null)) {
                    "작업 공간 선택 화면을 다시 열어 주세요."
                }
                action()
            }
        }
    }

    /** Classification is owned by workspace selection, including sidebar requests. */
    suspend fun confirmWorkspaceOpen(kind: WorkspaceKind): Result<Unit> {
        val request = fileManager.workspaceOpenRequest.value
            ?: return Result.failure(IllegalStateException("폴더 사용 방식 선택을 다시 열어 주세요."))
        if (openingWorkspaceSelection || workspaceOperationBusy() || request.busy || _vaultSelectionVisible.value) {
            return Result.failure(IllegalStateException("진행 중인 작업이 끝난 뒤 다시 시도해 주세요."))
        }
        val vault = bookmarks.value.vaultData?.toString()
        val generation = workspaceUiGeneration
        openingWorkspaceSelection = true
        navigationGate.newRequest()
        try {
            return fileReconciliationMutex.withLock {
                workspaceSaveCoordinator.runAfterFlush {
                    check(!workspaceOperationBusy()) { "진행 중인 작업이 끝난 뒤 다시 시도해 주세요." }
                    check(generation == workspaceUiGeneration && vault == bookmarks.value.vaultData?.toString() &&
                        fileManager.workspaceOpenRequest.value === request) {
                        "작업 공간이 변경되었습니다. 폴더를 다시 선택해 주세요."
                    }
                    fileManager.confirmWorkspaceOpen(kind)
                    if (fileManager.workspaceOpenRequest.value == null) completeWorkspaceSelection()
                }
            }
        } finally {
            openingWorkspaceSelection = false
        }
    }

    private data class CommittedFolderSettings(
        val context: WorkspaceReadContext,
        val originalKey: FolderKey,
        val name: String,
        val config: FolderConfig,
    )
    private var committedFolderSettings: CommittedFolderSettings? = null
    private var incompleteCreation: Pair<CreateFileRequest, FileCreationIncompleteException>? = null
    private val _canRetryCreation = MutableStateFlow(false)
    val canRetryCreation: StateFlow<Boolean> = _canRetryCreation.asStateFlow()

    private val _creationInProgress = MutableStateFlow(false)
    val creationInProgress: StateFlow<Boolean> = _creationInProgress.asStateFlow()

    init {
        addCloseable(workspaceSaveCoordinator.register {
            check(_documentConflicts.value.isEmpty()) { _documentConflicts.value.values.first() }
            saveCoordinator.flushAll()
        })

        viewModelScope.launch {
            fileManager.bookmarks.collectLatest { bookmarks ->
                fileReconciliationMutex.withLock {
                    var workspaceListsRefreshed = false
                    val vault = bookmarks.vaultData
                    if (vault == null) {
                        _projectList.value = emptyList()
                        _generalFolderList.value = emptyList()
                        activeVaultLocation = null
                    } else {
                        val vaultLocation = vault.toString()
                        if (activeVaultLocation != vaultLocation) {
                            activeVaultLocation = vaultLocation
                            refreshWorkspaceLists(vault)
                            workspaceListsRefreshed = true
                        }
                    }

                    if (_workspaceSelectionVisible.value) return@collectLatest
                    val project = bookmarks.projectData
                    if (project == null) {
                        if (activeProjectLocation != null) {
                            clearProjectState()
                            activeProjectLocation = null
                            activeWorkspaceKind = null
                        }
                        return@collectLatest
                    }
                    val projectLocation = project.toString()
                    val projectChanged = activeProjectLocation != projectLocation ||
                        activeWorkspaceKind != bookmarks.workspaceKind
                    if (projectChanged) {
                        // The screen can request this project's baseline before this collector
                        // finishes its initial IO. Keep that request: cancelling it back to Idle
                        // can be conflated by Compose and leave the loading gate without a job.
                        val preserveBaseline = bookmarks.workspaceKind == WorkspaceKind.PROJECT &&
                            _projectBaselineUiState.value.projectLocation == projectLocation
                        clearProjectState(preserveBaseline = preserveBaseline)
                        activeProjectLocation = projectLocation
                        activeWorkspaceKind = bookmarks.workspaceKind
                        if (!workspaceListsRefreshed) {
                            bookmarks.vaultData?.let { refreshWorkspaceLists(it) }
                        }
                    }
                    if (projectChanged || _hierarchyState.value.folderList.isEmpty()) {
                        val preferredKey = bookmarks.fileRelativePath
                            ?.let { runCatching { FileKey.of(it) }.getOrNull() }
                        refreshFoldersAndFiles(project, preferredKey, cancelRemoved = false)
                    } else {
                        val index = bookmarks.fileData?.let { selected ->
                            _hierarchyState.value.fileList.indexOfFirst {
                                it.platformFile.toString() == selected.toString()
                            }
                        } ?: -1
                        if (index >= 0) {
                            val current = _hierarchyState.value
                            _hierarchyState.value = current.copy(selectedFileKey = current.fileList[index].key)
                        }
                    }
                }
            }
        }

        // 프로젝트 설정 변경에 따른 현재 목록 재정렬은 ViewModel 수명 동안 한 번만 구독한다.
        viewModelScope.launch {
            projectConfig.collectLatest { config ->
                config ?: return@collectLatest
                fileReconciliationMutex.withLock {
                    val context = currentWorkspaceReadContext()
                    if (!isCurrentWorkspace(context)) return@withLock
                    val current = _hierarchyState.value
                    val contents = loadHierarchyFolderContents(current.folderList, config, context)
                    if (!isCurrentWorkspace(context)) return@withLock
                    publishHierarchy(current.copy(folderContents = contents))
                }
            }
        }

        // 외부 변경 감지: 활성(포커스) 상태에서만 동작.
        // 활성 전환 즉시 1회 검사(Phase 1) → 이후 주기 폴링(Phase 2). 비활성 시 collectLatest 가 루프를 취소.
        viewModelScope.launch {
            _active.collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    try {
                        checkExternalChanges()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        _workspaceTransitionError.value =
                            error.message ?: "외부 파일 변경을 확인하지 못했습니다."
                    }
                    delay(POLL_INTERVAL_MS.milliseconds)
                }
            }
        }
    }

    /** 앱/창 포커스 상태 전달 (MainScreen 의 LocalWindowInfo.isWindowFocused) */
    fun setActive(active: Boolean) {
        _active.value = active
    }

    /** 커밋 이력이 없는 관리 Project를 편집하기 전에 복원 가능한 최초 기준점을 만든다. */
    fun ensureInitialProjectBaseline() {
        if (workspaceInputBlocked) return
        val snapshot = bookmarks.value
        val project = snapshot.projectData ?: return
        if (snapshot.workspaceKind != WorkspaceKind.PROJECT) return
        val location = project.toString()
        when (val state = _projectBaselineUiState.value) {
            is ProjectBaselineUiState.Preparing -> if (state.projectLocation == location) return
            is ProjectBaselineUiState.Ready -> if (state.projectLocation == location) return
            else -> Unit
        }

        projectBaselineJob?.cancel()
        _projectBaselineUiState.value = ProjectBaselineUiState.Preparing(location)
        val context = currentWorkspaceReadContext()
        projectBaselineJob = viewModelScope.launch {
            try {
                fileReconciliationMutex.withLock {
                    if (currentWorkspaceReadContext() != context) return@withLock
                    saveCoordinator.flushAll()
                    if (currentWorkspaceReadContext() != context) return@withLock
                    projectCommitService.ensureInitialBaseline(project)
                }
                if (currentWorkspaceReadContext() == context) {
                    _projectBaselineUiState.value = ProjectBaselineUiState.Ready(location)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (currentWorkspaceReadContext() == context) {
                    _projectBaselineUiState.value = ProjectBaselineUiState.Error(
                        projectLocation = location,
                        message = error.message ?: "초기 기준점을 만들지 못했습니다.",
                    )
                }
            }
        }
    }

    /** 저장소 제약이 있는 경우 사용자가 위험을 인지하고 기준점 없이 편집을 계속한다. */
    fun skipInitialProjectBaseline() {
        val project = bookmarks.value.projectData ?: return
        if (bookmarks.value.workspaceKind != WorkspaceKind.PROJECT) return
        projectBaselineJob?.cancel()
        projectBaselineJob = null
        _projectBaselineUiState.value = ProjectBaselineUiState.Ready(
            projectLocation = project.toString(),
            skipped = true,
        )
    }

    fun dismissWorkspaceTransitionError() {
        workspaceSaveCoordinator.clearError()
        _workspaceTransitionError.value = null
    }

    fun openCommitDialog() {
        if (workspaceInputBlocked) return
        if (bookmarks.value.workspaceKind != WorkspaceKind.PROJECT) return
        val currentCreate = _commitCreateUiState.value
        if (currentCreate.isOpen && currentCreate.errorMessage == null) return
        if (_commitHistoryUiState.value.restore?.isRestoring == true) return
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        val request = beginCommitRequest()
        _commitHistoryUiState.value = _commitHistoryUiState.value.copy(
            isOpen = false,
            isLoading = false,
            diff = null,
            restore = null,
            errorMessage = null,
        )
        _commitCreateUiState.value = _commitCreateUiState.value.copy(
            isOpen = true,
            isLoading = true,
            isCommitting = false,
            preview = null,
            diff = null,
            errorMessage = null,
        )
        commitReadJob = viewModelScope.launch {
            try {
                val preview = fileReconciliationMutex.withLock {
                    saveCoordinator.flushAll()
                    if (!isCurrentCommitRequest(request, context)) return@withLock null
                    projectCommitService.preview(project)
                } ?: return@launch
                if (!isCurrentCommitRequest(request, context)) return@launch
                _commitCreateUiState.value = _commitCreateUiState.value.copy(
                    isLoading = false,
                    preview = preview,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentCommitRequest(request, context)) return@launch
                _commitCreateUiState.value = _commitCreateUiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "변경 사항을 확인하지 못했습니다.",
                )
            }
        }
    }

    fun dismissCommitDialog() {
        dismissCommitWorkspace()
    }

    fun dismissCommitWorkspace() {
        val create = _commitCreateUiState.value
        val history = _commitHistoryUiState.value
        if (create.isCommitting || history.restore?.isRestoring == true) return
        cancelCommitRequests()
        _commitCreateUiState.value = CommitCreateUiState()
        _commitHistoryUiState.value = CommitHistoryUiState()
    }

    fun updateCommitMessage(message: String) {
        val state = _commitCreateUiState.value
        if (!state.isOpen || state.isCommitting) return
        _commitCreateUiState.value = state.copy(message = message)
    }

    fun openCommitDiff(change: CommitChange) {
        val state = _commitCreateUiState.value
        if (!state.isOpen || state.isLoading || state.isCommitting) return
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        val request = beginCommitRequest()
        val target = CommitDiffUiState(
            commitId = null,
            fileId = change.fileId,
            displayPath = change.displayPath,
            isLoading = true,
        )
        _commitCreateUiState.value = state.copy(diff = target, errorMessage = null)
        commitReadJob = viewModelScope.launch {
            try {
                val result = fileReconciliationMutex.withLock {
                    saveCoordinator.flushAll()
                    if (!isCurrentCommitRequest(request, context)) return@withLock null
                    projectCommitService.diff(project, null, change.fileId)
                } ?: return@launch
                if (!isCurrentCommitRequest(request, context)) return@launch
                val current = _commitCreateUiState.value
                if (current.diff?.matches(target) == true) {
                    _commitCreateUiState.value = current.copy(
                        diff = target.copy(isLoading = false, result = result),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentCommitRequest(request, context)) return@launch
                val current = _commitCreateUiState.value
                if (current.diff?.matches(target) == true) {
                    _commitCreateUiState.value = current.copy(
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
        if (_commitCreateUiState.value.isCommitting) return
        cancelCommitRequests()
        _commitCreateUiState.value = _commitCreateUiState.value.copy(diff = null)
    }

    fun openCommitHistory() {
        if (workspaceInputBlocked) return
        if (bookmarks.value.workspaceKind != WorkspaceKind.PROJECT) return
        if (_commitHistoryUiState.value.isOpen || _commitCreateUiState.value.isCommitting) return
        _commitCreateUiState.value = _commitCreateUiState.value.copy(
            isOpen = false,
            isLoading = false,
            diff = null,
            errorMessage = null,
        )
        loadCommitHistory(selectedCommitId = _commitHistoryUiState.value.selectedCommitId)
    }

    fun retryCommitHistory() {
        if (!_commitHistoryUiState.value.isOpen) return
        loadCommitHistory(selectedCommitId = _commitHistoryUiState.value.selectedCommitId)
    }

    private fun loadCommitHistory(selectedCommitId: String?) {
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        val request = beginCommitRequest()
        val previous = _commitHistoryUiState.value
        _commitHistoryUiState.value = previous.copy(
            isOpen = true,
            isLoading = true,
            selectedCommitId = selectedCommitId,
            diff = null,
            restore = null,
            errorMessage = null,
        )
        commitReadJob = viewModelScope.launch {
            try {
                val loaded = fileReconciliationMutex.withLock {
                    saveCoordinator.flushAll()
                    if (!isCurrentCommitRequest(request, context)) return@withLock null
                    projectCommitService.preview(project) to projectCommitService.history(project)
                } ?: return@launch
                val (preview, history) = loaded
                if (!isCurrentCommitRequest(request, context)) return@launch
                _commitHistoryUiState.value = CommitHistoryUiState(
                    isOpen = true,
                    history = history,
                    workingPreview = preview,
                    selectedCommitId = selectedCommitId?.takeIf { selected ->
                        history.any { it.commit.id == selected }
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentCommitRequest(request, context)) return@launch
                _commitHistoryUiState.value = _commitHistoryUiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "커밋 이력을 읽지 못했습니다.",
                )
            }
        }
    }

    fun selectCommitHistoryEntry(commitId: String) {
        val state = _commitHistoryUiState.value
        if (!state.isOpen || state.isLoading || state.restore?.isRestoring == true) return
        _commitHistoryUiState.value = state.copy(
            selectedCommitId = commitId,
            diff = null,
            errorMessage = null,
        )
    }

    fun openCommitHistoryDiff(commitId: String, change: CommitChange) {
        val state = _commitHistoryUiState.value
        if (!state.isOpen || state.isLoading || state.restore?.isRestoring == true) return
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        val request = beginCommitRequest()
        val target = CommitDiffUiState(
            commitId = commitId,
            fileId = change.fileId,
            displayPath = change.displayPath,
            isLoading = true,
        )
        _commitHistoryUiState.value = state.copy(
            selectedCommitId = commitId,
            diff = target,
            errorMessage = null,
        )
        commitReadJob = viewModelScope.launch {
            try {
                val result = projectCommitService.diff(project, commitId, change.fileId)
                if (!isCurrentCommitRequest(request, context)) return@launch
                val current = _commitHistoryUiState.value
                if (current.diff?.matches(target) == true) {
                    _commitHistoryUiState.value = current.copy(
                        diff = target.copy(isLoading = false, result = result),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentCommitRequest(request, context)) return@launch
                val current = _commitHistoryUiState.value
                if (current.diff?.matches(target) == true) {
                    _commitHistoryUiState.value = current.copy(
                        diff = target.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "파일 diff를 읽지 못했습니다.",
                        ),
                    )
                }
            }
        }
    }

    fun navigateBackFromCommitHistory() {
        val state = _commitHistoryUiState.value
        if (state.restore?.isRestoring == true) return
        when {
            state.restore != null -> _commitHistoryUiState.value = state.copy(restore = null)
            state.diff != null -> {
                cancelCommitRequests()
                _commitHistoryUiState.value = state.copy(diff = null)
            }
            state.selectedCommitId != null ->
                _commitHistoryUiState.value = state.copy(selectedCommitId = null)
            else -> {
                dismissCommitWorkspace()
            }
        }
    }

    fun requestProjectRestore(entry: CommitHistoryEntry) {
        val state = _commitHistoryUiState.value
        if (!state.isOpen || state.isLoading || state.restore?.isRestoring == true) return
        val target = if (state.workingPreview?.parentCommitId == entry.commit.id) {
            CommitRestoreTarget.HeadSnapshot(entry)
        } else {
            CommitRestoreTarget.Project(entry)
        }
        _commitHistoryUiState.value = state.copy(
            restore = CommitRestoreUiState(target, state.workingPreview?.workingTreeHash ?: return),
            errorMessage = null,
        )
    }

    fun requestHeadRevert(entry: CommitHistoryEntry) {
        val state = _commitHistoryUiState.value
        if (!state.isOpen || state.isLoading || state.restore?.isRestoring == true) return
        if (entry.commit.parentId == null || state.history.firstOrNull()?.commit?.id != entry.commit.id) return
        _commitHistoryUiState.value = state.copy(
            restore = CommitRestoreUiState(CommitRestoreTarget.HeadChanges(entry), state.workingPreview?.workingTreeHash ?: return),
            errorMessage = null,
        )
    }

    fun requestFileContentRestore(change: CommitChange, side: CommitFileSide) {
        requestFileRestore(change, side, contentOnly = true)
    }

    fun requestFileRestore(change: CommitChange, side: CommitFileSide) {
        requestFileRestore(change, side, contentOnly = false)
    }

    private fun requestFileRestore(
        change: CommitChange,
        side: CommitFileSide,
        contentOnly: Boolean,
    ) {
        val state = _commitHistoryUiState.value
        val commitId = state.selectedCommitId ?: return
        if (!state.isOpen || state.isLoading || state.restore?.isRestoring == true) return
        val target = if (contentOnly) {
            CommitRestoreTarget.FileContent(commitId, change, side)
        } else {
            CommitRestoreTarget.File(commitId, change, side)
        }
        _commitHistoryUiState.value = state.copy(
            restore = CommitRestoreUiState(target, state.workingPreview?.workingTreeHash ?: return),
            errorMessage = null,
        )
    }

    fun dismissCommitRestore() {
        val state = _commitHistoryUiState.value
        if (state.restore?.isRestoring == true) return
        _commitHistoryUiState.value = state.copy(restore = null)
    }

    fun confirmCommitRestore() {
        if (workspaceInputBlocked) return
        if (bookmarks.value.workspaceKind != WorkspaceKind.PROJECT) return
        val state = _commitHistoryUiState.value
        val restore = state.restore ?: return
        if (restore.isRestoring) return
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        val request = beginCommitRequest()
        _commitHistoryUiState.value = state.copy(
            restore = restore.copy(isRestoring = true, errorMessage = null),
        )
        viewModelScope.launch {
            var restoreApplied = false
            try {
                val refreshed = fileReconciliationMutex.withLock {
                    if (!isCurrentCommitRequest(request, context)) return@withLock null
                    saveCoordinator.flushAll()
                    if (!isCurrentCommitRequest(request, context)) return@withLock null

                    saveCoordinator.withWritesPaused {
                        val selectedFileKey = _hierarchyState.value.currentFile?.key
                        val selectedFolderKey = _hierarchyState.value.currentFolderKey
                        suspend fun applyProjectResult() {
                            restoreApplied = true
                            invalidateAllEditorRuntimeForRestore()
                            refreshFoldersAndFiles(
                                project = project,
                                preferredKey = selectedFileKey,
                                preferredFolderKey = selectedFolderKey,
                                cancelRemoved = false,
                            )
                        }
                        suspend fun applyFileResult(result: FileRestoreResult) {
                            restoreApplied = true
                            reconcileSingleFileRestore(
                                project = project,
                                result = result,
                                selectedFileKey = selectedFileKey,
                                selectedFolderKey = selectedFolderKey,
                            )
                        }
                        when (val target = restore.target) {
                            is CommitRestoreTarget.Project -> {
                                projectCommitService.restore(
                                    project = project,
                                    commitId = target.entry.commit.id,
                                    expectedWorkingTreeHash = restore.expectedWorkingTreeHash,
                                )
                                applyProjectResult()
                            }
                            is CommitRestoreTarget.HeadSnapshot -> {
                                projectCommitService.restoreHeadSnapshot(
                                    project = project,
                                    commitId = target.entry.commit.id,
                                    expectedWorkingTreeHash = restore.expectedWorkingTreeHash,
                                )
                                applyProjectResult()
                            }
                            is CommitRestoreTarget.HeadChanges -> {
                                projectCommitService.revertHead(
                                    project = project,
                                    commitId = target.entry.commit.id,
                                    expectedWorkingTreeHash = restore.expectedWorkingTreeHash,
                                )
                                applyProjectResult()
                            }
                            is CommitRestoreTarget.FileContent -> {
                                val result = projectCommitService.restoreFileContent(
                                    project = project,
                                    commitId = target.commitId,
                                    fileId = target.change.fileId,
                                    side = target.side,
                                    expectedWorkingTreeHash = restore.expectedWorkingTreeHash,
                                )
                                applyFileResult(result)
                            }
                            is CommitRestoreTarget.File -> {
                                val result = projectCommitService.restoreFile(
                                    project = project,
                                    commitId = target.commitId,
                                    fileId = target.change.fileId,
                                    side = target.side,
                                    expectedWorkingTreeHash = restore.expectedWorkingTreeHash,
                                )
                                applyFileResult(result)
                            }
                        }
                        if (!isCurrentCommitRequest(request, context)) return@withWritesPaused null
                        reloadSelectedFileAfterRestore(context)
                        projectCommitService.preview(project) to projectCommitService.history(project)
                    }
                } ?: return@launch

                if (!isCurrentCommitRequest(request, context)) return@launch
                _commitHistoryUiState.value = _commitHistoryUiState.value.copy(
                    isLoading = false,
                    history = refreshed.second,
                    workingPreview = refreshed.first,
                    restore = null,
                    errorMessage = null,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentCommitRequest(request, context)) return@launch
                if (error is RestoreSessionStaleException) {
                    val refreshed = runCatching {
                        fileReconciliationMutex.withLock {
                            if (!isCurrentCommitRequest(request, context)) return@withLock null
                            projectCommitService.preview(project) to projectCommitService.history(project)
                        }
                    }.getOrNull()
                    if (!isCurrentCommitRequest(request, context)) return@launch
                    val current = _commitHistoryUiState.value
                    _commitHistoryUiState.value = current.copy(
                        isLoading = false,
                        history = refreshed?.second ?: current.history,
                        workingPreview = refreshed?.first ?: current.workingPreview,
                        restore = null,
                        errorMessage = error.message ?: "Project가 변경되어 복원을 취소했습니다.",
                    )
                    return@launch
                }
                val current = _commitHistoryUiState.value
                _commitHistoryUiState.value = if (restoreApplied) {
                    current.copy(
                        restore = null,
                        errorMessage = "복원은 완료했지만 화면을 새로고침하지 못했습니다. ${error.message.orEmpty()}",
                    )
                } else {
                    current.copy(
                        restore = current.restore?.copy(
                            isRestoring = false,
                            errorMessage = error.message ?: "선택한 상태로 복원하지 못했습니다.",
                        ),
                    )
                }
            }
        }
    }

    fun createCommit(message: String) {
        if (workspaceInputBlocked) return
        if (bookmarks.value.workspaceKind != WorkspaceKind.PROJECT) return
        val state = _commitCreateUiState.value
        val preview = state.preview ?: return
        if (state.isCommitting || !preview.hasChanges || message.isBlank()) return
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        val request = beginCommitRequest()
        _commitCreateUiState.value = state.copy(isCommitting = true, errorMessage = null)
        viewModelScope.launch {
            try {
                fileReconciliationMutex.withLock {
                    if (!isCurrentCommitRequest(request, context)) return@withLock
                    // 미리보기 이후 발생한 마지막 입력도 포함하고 service에서 tree를 다시 계산한다.
                    saveCoordinator.flushAll()
                    if (!isCurrentCommitRequest(request, context)) return@withLock
                    projectCommitService.commit(project, message)
                }
                if (!isCurrentCommitRequest(request, context)) return@launch
                _commitCreateUiState.value = CommitCreateUiState()
                _commitHistoryUiState.value = CommitHistoryUiState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentCommitRequest(request, context)) return@launch
                _commitCreateUiState.value = _commitCreateUiState.value.copy(
                    isCommitting = false,
                    errorMessage = error.message ?: "커밋을 만들지 못했습니다.",
                )
            }
        }
    }

    private suspend fun reconcileSingleFileRestore(
        project: PlatformFile,
        result: FileRestoreResult,
        selectedFileKey: FileKey?,
        selectedFolderKey: FolderKey?,
    ) {
        if (!result.changed) return
        val previousKey = result.previousPath?.toFileKeyOrNull()
        val restoredKey = result.restoredPath?.toFileKeyOrNull()
        if (previousKey != null && restoredKey != null && previousKey != restoredKey) {
            folderFileSelectionMemory.renameFile(previousKey, restoredKey)
        }
        invalidateRestoredFileRuntime(result)

        val selectedWasRestored = selectedFileKey != null &&
            (selectedFileKey == previousKey || selectedFileKey == restoredKey)
        val preferredKey = if (selectedWasRestored) restoredKey else selectedFileKey
        val preferredFolder = if (selectedWasRestored) {
            restoredKey?.folder ?: previousKey?.folder ?: selectedFolderKey
        } else {
            selectedFolderKey
        }
        refreshFoldersAndFiles(
            project = project,
            preferredKey = preferredKey,
            preferredFolderKey = preferredFolder,
            cancelRemoved = false,
        )
    }

    private suspend fun invalidateRestoredFileRuntime(result: FileRestoreResult) {
        if (!result.changed) return
        val keys = listOfNotNull(result.previousPath, result.restoredPath)
            .mapNotNull(String::toFileKeyOrNull)
            .toSet()
        keys.forEach(saveCoordinator::cancel)
        keys.mapNotNull { key -> pageLoadJobs.remove(key) }
            .forEach { job ->
                job.cancel()
                job.join()
            }
        _fileLoadStates.value = _fileLoadStates.value - keys
        keys.forEach { key ->
            knownProjectFiles.remove(key)
            knownModified.remove(key)
            editorSessionKeys.remove(key)
        }
    }

    private suspend fun invalidateAllEditorRuntimeForRestore() {
        saveCoordinator.cancelAll()
        val jobs = pageLoadJobs.values.toList()
        pageLoadJobs.clear()
        jobs.forEach(Job::cancel)
        jobs.joinAll()
        _fileLoadStates.value = emptyMap()
        // 하이라키는 refreshFoldersAndFiles의 새 snapshot으로 한 번에 교체한다.
        knownProjectFiles.clear()
        knownModified.clear()
        editorSessionKeys.clear()
        folderFileSelectionMemory.clear()
    }

    private suspend fun reloadSelectedFileAfterRestore(context: WorkspaceReadContext) {
        val selected = _hierarchyState.value.currentFile
        if (selected == null) {
            fileManager.clearPickedFile()
            return
        }
        fileManager.pickFile(selected)
        val noteFile = fileManager.readMarkdown(selected.platformFile)
        if (!isCurrentWorkspace(context)) return
        putLoadedNote(selected.key, noteFile)
        fileManager.lastModified(selected.platformFile)?.let { modified ->
            knownModified[selected.key] = modified
        }
    }

    private fun beginCommitRequest(): Long {
        commitReadJob?.cancel()
        commitReadJob = null
        return ++commitRequestGeneration
    }

    private fun cancelCommitRequests() {
        commitRequestGeneration++
        commitReadJob?.cancel()
        commitReadJob = null
    }

    private fun isCurrentCommitRequest(
        request: Long,
        context: WorkspaceReadContext,
    ): Boolean = request == commitRequestGeneration && isCurrentWorkspace(context)

    fun refreshProjectList() {
        viewModelScope.launch {
            val vault = bookmarks.value.vaultData ?: return@launch
            refreshWorkspaceLists(vault)
        }
    }

    private suspend fun refreshWorkspaceLists(vault: PlatformFile) {
        val locations = fileManager.listWorkspaceDirectories(vault)
        if (bookmarks.value.vaultData?.toString() != vault.toString()) return
        _projectList.value = locations.projectChoices
        _generalFolderList.value = locations.generalFolders
    }

    fun selectProject(project: PlatformFile) {
        launchWorkspaceTransition {
            fileManager.requestOpenWorkspace(project)
            if (fileManager.workspaceOpenRequest.value != null) {
                showWorkspaceSelection()
            }
        }
    }

    fun returnToLastProject() {
        launchWorkspaceTransition {
            fileManager.returnToLastProject()
            if (bookmarks.value.projectData == null) showWorkspaceSelection()
        }
    }

    fun navigateToProjectRoot() {
        selectFolder(FolderKey.Base)
    }

    fun selectFolder(folderKey: FolderKey) {
        launchNavigation { isLatest ->
            val folder = _hierarchyState.value.folderList.find { it.key == folderKey } ?: return@launchNavigation
            val content = loadFolderContent(folder)
            if (!isLatest()) return@launchNavigation
            val preferredKey = folderFileSelectionMemory.preferred(
                folderKey = folder.key,
                availableKeys = content.files.map(ProjectFile::key),
            )
            applyFolderContent(folder, content, preferredKey = preferredKey)
            content.files.getOrNull(_hierarchyState.value.currentIndex)?.let { selected ->
                fileManager.pickFile(selected)
            }
        }
    }

    fun selectFile(fileKey: FileKey) {
        launchNavigation { isLatest ->
            val folder = _hierarchyState.value.folderList.find { it.key == fileKey.folder }
                ?: return@launchNavigation
            val content = _hierarchyState.value.folderContents[folder.key]
                ?.takeIf { cached -> cached.files.any { it.key == fileKey } }
                ?: loadFolderContent(folder)
            val file = content.files.find { it.key == fileKey } ?: return@launchNavigation
            fileManager.pickFile(file)
            if (!isLatest()) return@launchNavigation
            applyFolderContent(folder, content, preferredKey = fileKey)
            folderFileSelectionMemory.remember(file.key)
        }
    }

    internal fun editorSessionKey(fileKey: FileKey): String =
        editorSessionKeys.getOrPut(fileKey) { "editor-${nextEditorSessionId++}" }

    fun retryCreation() {
        val pending = incompleteCreation ?: return
        if (_creationInProgress.value) return
        _creationInProgress.value = true
        launchWorkspaceMutation(onFinished = { _creationInProgress.value = false }) { _, context ->
            if (!pending.first.matchesWorkspace(context.projectLocation, context.workspaceKind)) return@launchWorkspaceMutation
            val folder = _hierarchyState.value.folderList.find { it.key == pending.first.folderKey }
                ?: error("생성 대상 폴더를 찾을 수 없습니다. 파일을 직접 확인해 주세요.")
            withContext(NonCancellable) {
                val created = try {
                    fileManager.retryProjectFileCreation(folder, pending.second)
                } catch (failure: FileCreationIncompleteException) {
                    incompleteCreation = pending.first to failure
                    throw IllegalStateException("${failure.message} 파일을 직접 확인한 뒤 작업 공간 선택으로 나갔다 다시 열면 새 파일을 생성할 수 있습니다.", failure)
                } catch (failure: Exception) {
                    throw IllegalStateException("${failure.message} 파일을 직접 확인한 뒤 작업 공간 선택으로 나갔다 다시 열면 새 파일을 생성할 수 있습니다.", failure)
                }
                incompleteCreation = null
                _canRetryCreation.value = false
                _workspaceTransitionError.value = null
                finishCreatedFile(folder, created, context)
            }
        }
    }

    private suspend fun finishCreatedFile(folder: ProjectFolder, created: ProjectFile, context: WorkspaceReadContext) {
        try {
            val noteFile = fileManager.readMarkdown(created.platformFile)
            if (!isCurrentWorkspace(context)) return
            putLoadedNote(created.key, noteFile)
            val freshContent = loadFolderContent(folder, context = context)
            applyFolderContent(folder, freshContent, preferredKey = created.key)
            fileManager.pickFile(created)
            if (context.workspaceKind == WorkspaceKind.GENERAL) {
                val workspace = bookmarks.value.projectData ?: return
                val sources = fileManager.generalSources.load(workspace)
                if (isCurrentWorkspace(context)) _generalSourceState.value = sources
            }
        } catch (error: Exception) {
            throw IllegalStateException("${created.key.relativePath} 파일은 생성됐지만 표시 갱신에 실패했습니다. 새 파일을 다시 생성하지 말고 폴더를 다시 열어 주세요.", error)
        }
    }

    fun createFileInCurrentFolder(title: String) {
        createFile(title, stage = null)
    }

    fun createPlotFile(stage: PlotStage, title: String) {
        createFile(title, stage)
    }

    fun createGeneralSourceFile(source: String?) {
        val workspace = bookmarks.value
        if (workspace.workspaceKind != WorkspaceKind.GENERAL) return
        val location = workspace.projectData?.toString() ?: return
        createFile(CreateFileRequest(location, WorkspaceKind.GENERAL, FolderKey.Base, initialSource = source), "무제")
    }

    private fun createFile(title: String, stage: PlotStage?) {
        val workspace = bookmarks.value
        val workspaceLocation = workspace.projectData?.toString() ?: return
        val folderKey = _hierarchyState.value.currentFolderKey ?: return
        createFile(CreateFileRequest(workspaceLocation, workspace.workspaceKind, folderKey, stage), title, stage)
    }

    internal fun createFile(
        request: CreateFileRequest,
        title: String,
        stage: PlotStage? = request.initialPlotStage,
    ) {
        val workspace = bookmarks.value
        if (!request.matchesWorkspace(workspace.projectData?.toString(), workspace.workspaceKind)) return
        if (!isValidProjectFileTitle(title)) return
        if (request.initialSource != null && request.workspaceKind != WorkspaceKind.GENERAL) return
        if (_creationInProgress.value) return
        if (incompleteCreation != null) {
            _workspaceTransitionError.value = "이전 파일의 설정 기록이 완료되지 않았습니다. 남은 기록 재시도를 사용해 주세요. 파일을 직접 확인한 뒤 작업 공간 선택으로 나갔다 다시 열면 새 파일을 생성할 수 있습니다."
            return
        }
        _creationInProgress.value = true
        launchWorkspaceMutation(onFinished = { _creationInProgress.value = false }) { _, context ->
            if (!request.matchesWorkspace(context.projectLocation, context.workspaceKind)) return@launchWorkspaceMutation
            val folder = _hierarchyState.value.folderList.find { it.key == request.folderKey }
                ?: return@launchWorkspaceMutation
            val config = folderConfig(folder.key)
            if (config.isPlot != (stage != null)) return@launchWorkspaceMutation
            val content = loadFolderContent(folder, context = context)
            val fileName = if (stage != null) {
                content.plotEntries.nextPlotFileName(stage, title)
            } else when (config.type) {
                FolderType.DEFAULT -> content.files.nextDefaultFileName(
                    title = title,
                    startAt = if (folder.key == FolderKey.Base) 0 else 1,
                )
                FolderType.GENERAL -> title
            }
            val initialNote = NoteFile.parse("")
            .let { note -> if (stage == null) note else note.withPlotStage(stage) }.withTags(autoTagsFor(folder.key))
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val created = try {
                    fileManager.createProjectFile(folder, fileName, initialNote, initialSource = request.initialSource)
                        ?: error("파일을 생성하지 못했습니다. 폴더 접근 권한과 최신 목록을 확인해 주세요.")
                } catch (failure: FileCreationIncompleteException) {
                    incompleteCreation = request to failure
                    _canRetryCreation.value = true
                    throw failure
                }
                finishCreatedFile(folder, created, context)
            }
        }
    }

    suspend fun saveDefaultOrder(
        folderKey: FolderKey,
        orderedFileKeys: List<FileKey>,
    ): Boolean {
        if (workspaceInputBlocked) return false
        val context = currentWorkspaceReadContext()
        return fileReconciliationMutex.withLock {
            if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock false
            val folder = _hierarchyState.value.folderList.find { it.key == folderKey } ?: return@withLock false
            val config = folderConfig(folderKey)
            if (config.type != FolderType.DEFAULT || config.isPlot) return@withLock false

            try {
                saveCoordinator.flush(orderedFileKeys.toSet())
                if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock false
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
    }

    suspend fun savePlotOrder(
        folderKey: FolderKey,
        assignments: List<PlotOrderAssignment>,
    ): Boolean {
        if (workspaceInputBlocked) return false
        val context = currentWorkspaceReadContext()
        return fileReconciliationMutex.withLock {
            if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock false
            val folder = _hierarchyState.value.folderList.find { it.key == folderKey } ?: return@withLock false
            if (!folderConfig(folderKey).isPlot) return@withLock false

            try {
                val assignmentKeys = assignments.mapTo(mutableSetOf()) { it.fileKey }
                saveCoordinator.flush(assignmentKeys)
                if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock false
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
    }

    fun createDirectory(name: String = "무제", folderConfig: FolderConfig = FolderConfig(type = FolderType.GENERAL)) {
        if (_creationInProgress.value) return
        _creationInProgress.value = true
        launchWorkspaceMutation(onFinished = { _creationInProgress.value = false }) { project, _ ->
            val created = fileManager.createProjectFolder(name, folderConfig)
                ?: error("폴더를 생성하지 못했습니다. 이름과 접근 권한을 확인해 주세요.")
            try {
                refreshFoldersAndFiles(project, preferredKey = null, cancelRemoved = false, preferredFolderKey = created.key)
            } catch (error: Exception) {
                throw IllegalStateException("${created.key.relativePath} 폴더는 생성됐지만 표시 갱신에 실패했습니다. 폴더 목록을 다시 열어 주세요.", error)
            }
        }
    }

    fun requestDeleteDirectory(folderKey: FolderKey) {
        if (workspaceInputBlocked) return
        val context = currentWorkspaceReadContext()
        viewModelScope.launch {
            fileReconciliationMutex.withLock {
                if (!isCurrentWorkspace(context)) return@withLock
                val preview = fileManager.inspectProjectFolderDeletion(folderKey)
                if (isCurrentWorkspace(context)) _pendingFolderDeletion.value = preview
            }
        }
    }

    fun dismissDeleteDirectory() {
        _pendingFolderDeletion.value = null
    }

    fun confirmDeleteDirectory() {
        val requested = _pendingFolderDeletion.value ?: return
        launchWorkspaceMutation { project, _ ->
            if (_pendingFolderDeletion.value != requested) return@launchWorkspaceMutation
            val preview = fileManager.inspectProjectFolderDeletion(requested.folder.key)
                ?: return@launchWorkspaceMutation
            if (!preview.canDelete) {
                _pendingFolderDeletion.value = preview
                return@launchWorkspaceMutation
            }
            val result = fileManager.deleteProjectFolder(preview.folder.key) ?: return@launchWorkspaceMutation
            folderFileSelectionMemory.forget(result.folderKey)

            result.deletedFileKeys.forEach { key ->
                saveCoordinator.cancel(key)
                knownProjectFiles.remove(key)
                knownModified.remove(key)
            }
            _fileLoadStates.value = _fileLoadStates.value - result.deletedFileKeys.toSet()
            _pendingFolderDeletion.value = null

            val previousFolderKey = _hierarchyState.value.currentFolderKey
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

    /** Flush the captured file before presenting a destructive confirmation for that exact identity. */
    fun requestMoveFileToTrash(file: ProjectFile) {
        if (workspaceInputBlocked || fileTrashPreparationInProgress || _pendingFileTrash.value != null) return
        val context = currentWorkspaceReadContext()
        if (!isCurrentHierarchyFile(file, context)) return
        fileTrashPreparationInProgress = true
        viewModelScope.launch {
            try {
                fileReconciliationMutex.withLock {
                    if (!isCurrentHierarchyFile(file, context)) return@withLock
                    workspaceSaveCoordinator.runAfterFlush {
                        saveCoordinator.flushAll()
                        check(isCurrentHierarchyFile(file, context)) { "삭제할 파일이 변경되었거나 더 이상 존재하지 않습니다." }
                        _pendingFileTrash.value = FileTrashUiState(file = file)
                    }.onFailure { error ->
                        if (isCurrentWorkspace(context) && workspaceSaveCoordinator.lastErrorMessage.value == null) {
                            _workspaceTransitionError.value = error.message ?: "파일 삭제를 준비하지 못했습니다."
                        }
                    }
                }
            } finally {
                fileTrashPreparationInProgress = false
            }
        }
    }

    fun dismissFileTrash() {
        if (_pendingFileTrash.value?.busy != true) _pendingFileTrash.value = null
    }

    fun confirmMoveFileToTrash() {
        val requested = _pendingFileTrash.value ?: return
        if (requested.busy) return
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        _pendingFileTrash.value = requested.copy(busy = true, errorMessage = null)
        viewModelScope.launch {
            fileReconciliationMutex.withLock {
                if (!isCurrentWorkspace(context)) return@withLock
                workspaceSaveCoordinator.runAfterFlush {
                    saveCoordinator.flushAll()
                    val currentRequest = _pendingFileTrash.value
                    check(currentRequest?.file?.sameIdentityAs(requested.file) == true) { "삭제 요청이 변경되었습니다." }
                    check(isCurrentHierarchyFile(requested.file, context)) { "삭제할 파일이 변경되었거나 더 이상 존재하지 않습니다." }
                    val selectionBeforeMove = _hierarchyState.value.selectedFileKey
                    val folderBeforeMove = _hierarchyState.value.currentFolderKey
                    val result = fileManager.moveProjectFileToTrash(requested.file.key)
                    val key = requested.file.key
                    saveCoordinator.cancel(key)
                    pageLoadJobs.remove(key)?.cancel()
                    knownProjectFiles.remove(key)
                    knownModified.remove(key)
                    editorSessionKeys.remove(key)
                    _fileLoadStates.value = _fileLoadStates.value - key
                    _pendingFileTrash.value = null

                    val refreshFailure = runCatching {
                        refreshFoldersAndFiles(
                            project = project,
                            preferredKey = selectionBeforeMove?.takeUnless { it == key },
                            cancelRemoved = true,
                            preferredFolderKey = if (selectionBeforeMove == key) key.folder else folderBeforeMove,
                        )
                    }.exceptionOrNull()
                    val messages = listOfNotNull(
                        result.cleanupWarning,
                        refreshFailure?.let { "파일은 휴지통으로 이동했지만 목록을 새로 고치지 못했습니다. ${it.message.orEmpty()}" },
                    )
                    if (messages.isNotEmpty()) _workspaceTransitionError.value = messages.joinToString("\n")
                }.onFailure { error ->
                    if (isCurrentWorkspace(context) && _pendingFileTrash.value?.file?.sameIdentityAs(requested.file) == true) {
                        _pendingFileTrash.value = requested.copy(
                            busy = false,
                            errorMessage = error.message ?: "파일을 휴지통으로 이동하지 못했습니다.",
                        )
                    }
                }
            }
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
            if (workspaceInputBlocked) return@withLock
            val workspace = bookmarks.value
            val project = workspace.projectData ?: return@withLock
            val context = currentWorkspaceReadContext()
            if (!isCurrentWorkspace(context)) return@withLock
            if (!reconcileSelectedWorkspaceAvailability()) return@withLock

            // 1. 폴더·파일 목록 갱신 (외부 추가/삭제 반영, 현재 FileKey 보존)
            refreshFoldersAndFiles(project, preferredKey = null, cancelRemoved = true)
            if (!isCurrentWorkspace(context)) return@withLock

            // 2. 캐시된 파일들의 내용 변경 감지
            for (projectFile in _hierarchyState.value.fileList) {
                if (!isCurrentWorkspace(context)) return@withLock
                val key = projectFile.key
                if (key in _documentConflicts.value) continue
                val file = projectFile.platformFile
                val cached = loadedNote(key) ?: continue // 아직 안 연 파일은 skip
                val diskModified = fileManager.lastModified(file) ?: continue
                if (knownModified[key] == diskModified) continue // 변화 없음 (자기 쓰기 포함)

                // external wins: 외부 mtime 변경을 보면 이 파일의 stale pending write를 먼저 취소.
                saveCoordinator.cancel(key)
                val fresh = fileManager.readMarkdown(file)
                if (!isCurrentWorkspace(context)) return@withLock
                knownModified[key] = fileManager.lastModified(file) ?: diskModified
                // mtime 은 달라졌지만 내용은 동일할 수 있음(자기 쓰기 레이스 등) → 실제 diff 일 때만 교체
                if (fresh.inject() != cached.inject()) {
                    putLoadedNote(key, fresh)
                }
            }
        }
    }

    /** 외부에서 현재 Vault 직속 폴더를 지웠을 때 stale 저장 예약과 선택 상태를 함께 폐기한다. */
    internal suspend fun reconcileSelectedWorkspaceAvailability(): Boolean {
        val workspace = fileManager.bookmarks.value.projectData ?: return true
        if (fileManager.workspaceExists(workspace)) return true
        saveCoordinator.cancelAll()
        clearProjectState()
        fileManager.clearMissingWorkspace(workspace)
        return false
    }

    fun onPageChanged(index: Int) {
        launchNavigation { isLatest ->
            val file = _hierarchyState.value.fileList.getOrNull(index) ?: return@launchNavigation
            fileManager.pickFile(file)
            if (!isLatest()) return@launchNavigation
            _hierarchyState.value = _hierarchyState.value.copy(selectedFileKey = file.key)
            folderFileSelectionMemory.remember(file.key)
        }
    }

    fun loadPage(projectFile: ProjectFile) {
        loadPage(projectFile, force = false)
    }

    fun retryPage(projectFile: ProjectFile) {
        if (projectFile.key in _documentConflicts.value) return
        loadPage(projectFile, force = true)
    }

    /** Explicit discard/reload only: ordinary polling and navigation never discard a conflicted editor draft. */
    fun reloadConflictedDocument(fileKey: FileKey) {
        if (fileKey !in _documentConflicts.value) return
        val context = currentWorkspaceReadContext()
        viewModelScope.launch {
            fileReconciliationMutex.withLock {
                if (!isCurrentWorkspace(context) || fileKey !in _documentConflicts.value) return@withLock
                val file = knownProjectFiles[fileKey]
                val exists = try { file?.platformFile?.exists() == true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    _workspaceTransitionError.value = failure.message ?: "문서 위치를 확인하지 못했습니다. 초안을 유지했습니다."
                    return@withLock
                }
                saveCoordinator.cancel(fileKey)
                pageLoadJobs.remove(fileKey)?.cancel()
                _documentConflicts.value = _documentConflicts.value - fileKey
                if (_documentConflicts.value.isEmpty()) workspaceSaveCoordinator.clearError()
                if (exists && file != null) loadPage(file, force = true)
                else {
                    // The explicit discard also resolves an externally removed/renamed original path.
                    _fileLoadStates.value = _fileLoadStates.value - fileKey
                    knownProjectFiles.remove(fileKey)
                    knownModified.remove(fileKey)
                    editorSessionKeys.remove(fileKey)
                }
            }
        }
    }

    private fun retainConflictedPendingWrite(fileKey: FileKey, message: String) {
        val pending = saveCoordinator.cancel(fileKey) ?: return
        _documentConflicts.value = _documentConflicts.value + (fileKey to message)
        saveCoordinator.schedule(fileKey, pending)
        workspaceSaveCoordinator.reportAutoSaveFailure(fileKey.relativePath, IllegalStateException(message))
    }

    private fun loadPage(projectFile: ProjectFile, force: Boolean) {
        if (workspaceInputBlocked) return
        val key = projectFile.key
        val file = projectFile.platformFile
        val context = currentWorkspaceReadContext()
        if (!isCurrentWorkspace(context)) return
        knownProjectFiles[key] = projectFile
        val currentState = _fileLoadStates.value[key]
        if (!force && (currentState is FileLoadUiState.Loading || currentState is FileLoadUiState.Loaded)) {
            return
        }
        pageLoadJobs.remove(key)?.cancel()
        setFileLoadState(key, FileLoadUiState.Loading)

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val markdown = fileManager.readMarkdown(file)
                if (!isCurrentProjectFile(projectFile, context)) return@launch
                putLoadedNote(key, markdown)
                fileManager.lastModified(file)?.let { knownModified[key] = it }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentProjectFile(projectFile, context)) return@launch
                setFileLoadState(
                    key,
                    FileLoadUiState.Error(error.message ?: "파일을 읽지 못했습니다."),
                )
            } finally {
                val runningJob = currentCoroutineContext().job
                if (pageLoadJobs[key] === runningJob) pageLoadJobs.remove(key)
            }
        }
        pageLoadJobs[key] = job
        job.start()
    }

    fun updateBody(fileKey: FileKey, newBody: String) {
        if (_commitHistoryUiState.value.restore?.isRestoring == true) return
        if (workspaceInputBlocked) return
        val current = loadedNote(fileKey) ?: return
        if (current.body == newBody) return
        // 예약된 저장은 나중에 바뀐 bookmark가 아니라 편집 시점의 정책을 따른다.
        val workspace = bookmarks.value
        val bodyUpdated = current.withBody(newBody, ensureId = false)
        val updated = if (workspace.workspaceKind == WorkspaceKind.PROJECT) {
            bodyUpdated.withProjectMetadata(workspace.projectData?.name)
        } else bodyUpdated
        putLoadedNote(fileKey, updated)
        val projectFile = knownProjectFiles[fileKey] ?: return
        saveCoordinator.schedule(fileKey, PendingWrite(projectFile, updated))
    }

    /** Compare only frontmatter, then merge the most recent editor body under the shared write fence. */
    suspend fun saveDocumentProperties(fileKey: FileKey, expectedNote: NoteFile, updatedNote: NoteFile): String? {
        val context = currentWorkspaceReadContext()
        if (workspaceInputBlocked) return "작업 공간을 다시 열어 주세요."
        return fileReconciliationMutex.withLock {
            if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock "작업 공간이 변경되었습니다."
            val file = knownProjectFiles[fileKey] ?: return@withLock "문서를 다시 열어 주세요."
            // Detect external changes before flushing a pending body snapshot that still contains old properties.
            try {
                saveCoordinator.withWritesPaused {
                    val beforeFlush = fileManager.readMarkdown(file.platformFile)
                    val cached = loadedNote(fileKey)
                    if (beforeFlush.withBody("", false).inject() != expectedNote.withBody("", false).inject()) {
                        if (cached != null && cached.withBody("", false).inject() != beforeFlush.withBody("", false).inject()) {
                            retainConflictedPendingWrite(fileKey, "${fileKey.relativePath}: 외부 속성과 충돌했습니다. 본문 초안을 보존했으며, 디스크 문서를 다시 읽기 전에는 작업 공간을 전환할 수 없습니다.")
                        }
                        error("문서 속성이 변경되었습니다. 최신 속성을 확인한 뒤 다시 시도해 주세요.")
                    }
                    if (cached != null && cached.body != expectedNote.body && beforeFlush.body != expectedNote.body && cached.body != beforeFlush.body) {
                        retainConflictedPendingWrite(fileKey, "${fileKey.relativePath}: 외부 본문과 충돌했습니다. 본문 초안을 보존했으며, 디스크 문서를 다시 읽기 전에는 작업 공간을 전환할 수 없습니다.")
                        error("본문이 외부에서도 변경되었습니다. 최신 문서를 확인한 뒤 다시 시도해 주세요.")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { return@withLock failure.message ?: "문서를 읽지 못했습니다." }
            val result = workspaceSaveCoordinator.runAfterFlush {
                check(isCurrentProjectFile(file, context)) { "문서 위치가 변경되었습니다." }
                saveCoordinator.withWritesPaused {
                    val disk = fileManager.readMarkdown(file.platformFile)
                    check(isCurrentProjectFile(file, context)) { "작업 공간이 변경되었습니다." }
                    check(disk.withBody("", false).inject() == expectedNote.withBody("", false).inject()) {
                        "문서 속성이 변경되었습니다. 최신 속성을 확인한 뒤 다시 시도해 주세요."
                    }
                    val managed = if (context.workspaceKind == WorkspaceKind.PROJECT) {
                        com.ninetag.machum.entity.normalizeTags(
                            listOfNotNull(bookmarks.value.projectData?.name) + projectConfig.value?.effectiveAutoTags(fileKey.folder.relativePath).orEmpty())
                    } else emptyList()
                    val protection = DocumentPropertyProtectionPolicy(
                        managedTagNames = managed.toSet(),
                        sourceIsManaged = context.workspaceKind != WorkspaceKind.PROJECT && _generalSourceState.value?.enabled == true,
                    )
                    protection.persistedChangeError(disk, updatedNote)?.let { error(it) }
                    val latest = loadedNote(fileKey)
                    val body = when {
                        latest == null || latest.body == expectedNote.body -> disk.body
                        disk.body == expectedNote.body || latest.body == disk.body -> latest.body
                        else -> error("본문이 외부에서도 변경되었습니다. 최신 문서를 확인한 뒤 다시 시도해 주세요.")
                    }
                    val merged = updatedNote.withBody(body, false)
                    if (merged.inject() != disk.inject()) withContext(NonCancellable) {
                        pageLoadJobs.remove(fileKey)?.cancel()
                        writeNote(file.platformFile, merged)
                        // Input may arrive while provider IO is suspended. Rebase that body on the committed properties.
                        val after = loadedNote(fileKey)
                        val rebased = merged.withBody(if (after != null && after.body != latest?.body) after.body else merged.body, false)
                        saveCoordinator.cancel(fileKey)
                        putLoadedNote(fileKey, rebased)
                        if (rebased.body != merged.body) saveCoordinator.schedule(fileKey, PendingWrite(file, rebased))
                        fileManager.lastModified(file.platformFile)?.let { knownModified[fileKey] = it }
                    }
                }
                if (context.workspaceKind == WorkspaceKind.GENERAL && isCurrentWorkspace(context)) {
                    _generalSourceState.value = fileManager.generalSources.load(fileManager.bookmarks.value.projectData!!)
                }
            }
            result.exceptionOrNull()?.message
        }
    }

    suspend fun createGeneralSourceGroup(): String? = generalSourceMutation { project ->
        _generalSourceState.value = fileManager.generalSources.createGroup(project)
    }

    suspend fun planGeneralSourceRename(oldName: String, newName: String): GeneralSourcePlan =
        prepareGeneralSourcePlan { fileManager.generalSources.planRename(it, oldName, newName) }

    suspend fun planGeneralSourceDelete(name: String): GeneralSourcePlan =
        prepareGeneralSourcePlan { fileManager.generalSources.planDelete(it, name) }

    suspend fun planGeneralSourceAssign(file: ProjectFile, target: String): GeneralSourcePlan =
        prepareGeneralSourcePlan { fileManager.generalSources.planAssign(it, listOf(file), target) }

    private suspend fun prepareGeneralSourcePlan(action: suspend (PlatformFile) -> GeneralSourcePlan): GeneralSourcePlan {
        var plan: GeneralSourcePlan? = null
        val error = generalSourceMutation { plan = action(it) }
        check(error == null && plan != null) { error ?: "구분 작업을 준비하지 못했습니다." }
        return plan
    }

    suspend fun assignGeneralSource(file: ProjectFile, target: String): String? = generalSourceMutation { project ->
        applyGeneralSourcePlanLocked(fileManager.generalSources.planAssign(project, listOf(file), target))
    }

    suspend fun applyGeneralSourcePlan(plan: GeneralSourcePlan): String? = generalSourceMutation {
        applyGeneralSourcePlanLocked(plan)
    }

    private suspend fun applyGeneralSourcePlanLocked(plan: GeneralSourcePlan) {
        saveCoordinator.withWritesPaused {
          withContext(NonCancellable) {
            val result = fileManager.generalSources.apply(plan)
            for (file in result.changedFiles) {
                pageLoadJobs.remove(file.key)?.cancel()
                val fresh = fileManager.readMarkdown(file.platformFile)
                val pending = saveCoordinator.cancel(file.key)
                val merged = fresh.withBody(pending?.noteFile?.body ?: fresh.body, false)
                if (file.key in _fileLoadStates.value) putLoadedNote(file.key, merged)
                if (pending != null) saveCoordinator.schedule(file.key, PendingWrite(file, merged))
                fileManager.lastModified(file.platformFile)?.let { knownModified[file.key] = it }
            }
            result.state?.let { _generalSourceState.value = it }
            check(result.complete) { result.error ?: result.failures.entries.joinToString("\n") { "${it.key.relativePath}: ${it.value}" } }
          }
        }
    }

    private suspend fun generalSourceMutation(action: suspend (PlatformFile) -> Unit): String? {
        val context = currentWorkspaceReadContext()
        if (workspaceInputBlocked || context.workspaceKind != WorkspaceKind.GENERAL) return "General 작업 공간을 선택해 주세요."
        return fileReconciliationMutex.withLock {
            val result = workspaceSaveCoordinator.runAfterFlush {
                check(!workspaceInputBlocked && isCurrentWorkspace(context)) { "작업 공간이 변경되었습니다." }
                action(bookmarks.value.projectData ?: error("작업 공간을 찾을 수 없습니다."))
            }
            result.exceptionOrNull()?.message
        }
    }

    fun updateDirectory(folderKey: FolderKey, updatedName: String, folderConfig: FolderConfig) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            updateDirectoryAndAwait(folderKey, updatedName, folderConfig)
        }
    }

    suspend fun updateFolderAutoTagsAndAwait(folderKey: FolderKey, tags: List<String>): Boolean =
        updateDirectoryAndAwait(folderKey, folderKey.relativePath, FolderConfig(), reportErrors = false) {
            it.copy(autoTags = com.ninetag.machum.entity.normalizeTags(tags))
        }

    internal suspend fun updateFolderTypeAndAwait(folderKey: FolderKey, presentation: FolderPresentation): Boolean =
        updateDirectoryAndAwait(folderKey, folderKey.relativePath, FolderConfig(), reportErrors = false, transform = presentation::apply)

    suspend fun updateDirectoryAndAwait(
        folderKey: FolderKey,
        updatedName: String,
        folderConfig: FolderConfig,
        reportErrors: Boolean = true,
        transform: ((FolderConfig) -> FolderConfig)? = null,
    ): Boolean {
        var updated = false
        val requestedContext = currentWorkspaceReadContext()
        val previousCommit = committedFolderSettings?.takeIf {
            it.context == requestedContext && it.originalKey == folderKey &&
                it.name == updatedName && (transform?.invoke(it.config) ?: folderConfig) == it.config
        }
        if (previousCommit == null) committedFolderSettings = null
        runWorkspaceMutation(context = requestedContext, reportErrors = reportErrors) { project, context ->
            // runAfterFlush has completed the pending writes from the committed rename/config.
            // Do not re-read/re-tag an item at the new path on a retry of that same request.
            if (previousCommit != null && committedFolderSettings === previousCommit) {
                committedFolderSettings = null
                _workspaceTransitionError.value = null
                updated = true
                return@runWorkspaceMutation
            }
            val previousConfig = projectConfig.value ?: return@runWorkspaceMutation
            val requestedConfig = transform?.invoke(previousConfig.folders[folderKey.relativePath] ?: FolderConfig()) ?: folderConfig
            val pendingKeys = saveCoordinator.withWritesPaused {
                // 사전 조회는 취소 가능하다. 설정 확정 뒤에 실패할 수 있는 본문 조회를 남기지 않는다.
                val plan = folderSettingsService.prepare(project, previousConfig, folderKey, updatedName, requestedConfig)
                    ?: return@withWritesPaused null
                currentCoroutineContext().ensureActive()
                if (!isCurrentWorkspace(context)) return@withWritesPaused null
                // 물리 경로가 바뀐 뒤에는 pending/cache의 새 경로 반영까지 반드시 완료한다.
                withContext(NonCancellable) {
                    val result = folderSettingsService.commit(plan) ?: return@withContext null
                    applyFolderSettings(plan, result, project.name).also {
                        committedFolderSettings = CommittedFolderSettings(
                            context, folderKey, updatedName, requestedConfig,
                        )
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (pendingKeys == null) {
                if (reportErrors) _workspaceTransitionError.value =
                    "디렉터리 설정을 변경하지 못했습니다. 이름과 폴더 접근 권한을 확인해 주세요."
                return@runWorkspaceMutation
            }
            try {
                // 같은 mutex를 재진입하지 않도록 fence를 해제한 뒤 저장한다.
                saveCoordinator.flush(pendingKeys)
                updated = true
                committedFolderSettings = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (reportErrors) _workspaceTransitionError.value = "디렉터리 설정은 변경했지만 일부 문서 저장에 실패했습니다. " +
                    "내용은 메모리에 보관 중이며 다음 편집이나 작업 전환 시 다시 저장합니다. ${error.message.orEmpty()}"
            }
        }
        return updated
    }

    /** 모든 IO 뒤에 호출한다. 최신 pending을 옮기는 동안 suspend/재조회로 UI 입력을 끼워 넣지 않는다. */
    private fun applyFolderSettings(
        plan: FolderSettingsPlan,
        result: FolderSettingsUpdate,
        projectName: String,
    ): Set<FileKey> {
        val oldFolderKey = plan.folder.key
        val newFolderKey = result.folder.key
        val current = _hierarchyState.value
        val states = _fileLoadStates.value.toMutableMap()
        val pendingKeys = mutableSetOf<FileKey>()
        result.filesByPreviousKey.forEach { (oldKey, projectFile) ->
            val key = projectFile.key
            val pending = saveCoordinator.cancel(oldKey)
            val cached = loadedNote(oldKey)
            val snapshot = plan.files[oldKey]
            // cache가 로드된 뒤 외부에서 저장했을 수 있다. 앱의 미저장 편집이 없으면 사전 조회한 원문을 우선한다.
            val original = pending?.noteFile ?: snapshot?.noteFile?.let { disk ->
                cached?.takeIf { it.inject() == disk.inject() } ?: disk
            } ?: cached
            val updated = if (bookmarks.value.workspaceKind == WorkspaceKind.PROJECT) {
                original?.withManagedTagChanges(
                    projectName,
                    plan.previousConfig.effectiveAutoTags(oldKey.folder.relativePath),
                    result.projectConfig.effectiveAutoTags(key.folder.relativePath),
                )
            } else original
            moveEditorSessionKey(oldKey, key)
            pageLoadJobs.remove(oldKey)?.cancel()
            states.remove(oldKey)
            if (updated != null) states[key] = FileLoadUiState.Loaded(updated)
            knownProjectFiles.remove(oldKey)
            knownProjectFiles[key] = projectFile
            val previousModified = knownModified.remove(oldKey)
            val modified = snapshot?.modified ?: previousModified
            modified?.let { knownModified[key] = it }
            if (updated != null && (pending != null || updated !== original)) {
                saveCoordinator.schedule(key, PendingWrite(projectFile, updated))
                pendingKeys += key
            }
        }
        _fileLoadStates.value = states
        if (oldFolderKey != newFolderKey) folderFileSelectionMemory.renameFolder(oldFolderKey, newFolderKey)
        val folders = current.folderList.map { if (it.key == oldFolderKey) result.folder else it }
            .sortedBy { it.key.relativePath }
        val contents = current.folderContents.toMutableMap().apply { remove(oldFolderKey) }
        val affectedFolders = if (oldFolderKey == FolderKey.Base) folders else listOf(result.folder)
        affectedFolders.forEach { folder ->
            val files = result.filesByPreviousKey.values.filter { it.key.folder == folder.key }
            val config = folderConfig(folder.key, result.projectConfig, currentWorkspaceReadContext())
            contents[folder.key] = if (config.isPlot) {
                val entries = files.map { file ->
                    PlotFileEntry(file, loadedNote(file.key)?.plotStage, file.plotOrder())
                }.sortedForPlot()
                HierarchyFolderContent(entries.map(PlotFileEntry::projectFile), entries)
            } else HierarchyFolderContent(files.sortedFor(config))
        }
        val selectedKey = current.selectedFileKey?.let { result.filesByPreviousKey[it]?.key ?: it }
        publishHierarchy(
            next = current.copy(
                folderList = folders,
                folderContents = contents,
                currentFolderKey = if (current.currentFolderKey == oldFolderKey) newFolderKey else current.currentFolderKey,
                selectedFileKey = selectedKey,
            ),
        )
        return pendingKeys
    }

    suspend fun renameFile(projectFile: ProjectFile, newName: String): String? {
        _documentConflicts.value[projectFile.key]?.let { return it }
        if (workspaceInputBlocked) return "작업 공간 선택 중에는 이름을 변경할 수 없습니다."
        val context = currentWorkspaceReadContext()
        val result = fileReconciliationMutex.withLock {
            _documentConflicts.value[projectFile.key]?.let { return@withLock it }
            if (!isCurrentProjectFile(projectFile, context)) return@withLock "파일 선택이 변경되었습니다."
            // 대기는 취소 가능하며, 이미 시작된 쓰기가 끝나기 전에는 pending을 꺼내거나 rename하지 않는다.
            saveCoordinator.withWritesPaused {
                currentCoroutineContext().ensureActive()
                if (!isCurrentProjectFile(projectFile, context)) {
                    return@withWritesPaused "파일 선택이 변경되었습니다."
                }
                // 물리 rename 이후 UI coroutine이 취소돼도 경로·pending·bookmark 반영을 끝낸다.
                withContext(NonCancellable) { renameFileWithWritesPaused(projectFile, newName) }
            }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun renameFileWithWritesPaused(projectFile: ProjectFile, newName: String): String? {
        val file = projectFile.platformFile
        if (file.nameWithoutExtension == newName) return null
        projectFileTitleError(newName)?.let { return it }
        val targetFileName = "$newName.md"
        if (_hierarchyState.value.fileList.any { candidate ->
                candidate.key != projectFile.key &&
                    candidate.key.fileName.equals(targetFileName, ignoreCase = true)
            }
        ) {
            return "같은 이름의 파일이 이미 있습니다."
        }

        val oldKey = projectFile.key
        val pending = saveCoordinator.cancel(oldKey)
        val renamed = runCatching { fileManager.renameFile(projectFile, newName) }.getOrNull()
        if (renamed == null) {
            (saveCoordinator.cancel(oldKey) ?: pending)?.let {
                saveCoordinator.schedule(oldKey, it)
            }
            return "파일 이름을 변경하지 못했습니다. 이름과 폴더 접근 권한을 확인해 주세요."
        }
        val renamedModified = fileManager.lastModified(renamed)
        val newKey = oldKey.rename(renamed.name)
        folderFileSelectionMemory.renameFile(oldKey, newKey)
        moveEditorSessionKey(oldKey, newKey)
        val renamedProjectFile = ProjectFile(newKey, renamed)
        knownProjectFiles.remove(oldKey)
        knownProjectFiles[newKey] = renamedProjectFile
        // 다시 읽지 않고 캐시를 옮긴다. rename I/O 중 들어온 최신 pending도 함께 이동한다.
        val cached = loadedNote(oldKey)
        val pendingToMove = saveCoordinator.cancel(oldKey) ?: pending
        if (cached != null) {
            _fileLoadStates.value = _fileLoadStates.value.toMutableMap().also {
                it.remove(oldKey)
                it[newKey] = FileLoadUiState.Loaded(cached)
            }
        }
        if (pendingToMove != null) {
            saveCoordinator.schedule(newKey, pendingToMove.copy(projectFile = renamedProjectFile))
        }
        knownModified.remove(oldKey)
        renamedModified?.let { knownModified[newKey] = it }

        // cache와 editor session을 유지하고 목록·선택만 새 key로 바꾼다.
        val current = _hierarchyState.value
        val selectedKey = current.selectedFileKey
        val replacedFiles = current.fileList.map { candidate ->
            if (candidate.key == oldKey) renamedProjectFile else candidate
        }
        val config = folderConfig(oldKey.folder)
        val plotEntries = if (config.isPlot) {
            current.plotFileEntries
                .map { entry ->
                    if (entry.projectFile.key == oldKey) entry.copy(projectFile = renamedProjectFile) else entry
                }
                .sortedForPlot()
        } else {
            emptyList()
        }
        val orderedFiles = if (plotEntries.isNotEmpty()) {
            plotEntries.map(PlotFileEntry::projectFile)
        } else {
            replacedFiles.sortedFor(config)
        }
        publishHierarchy(
            next = current.copy(
                folderContents = current.folderContents +
                    (oldKey.folder to HierarchyFolderContent(orderedFiles, plotEntries)),
            ),
            preferredKey = if (selectedKey == oldKey) newKey else selectedKey,
        )
        runCatching { fileManager.pickFile(renamedProjectFile) }
        return null
    }

    private suspend fun refreshFoldersAndFiles(
        project: PlatformFile,
        preferredKey: FileKey?,
        cancelRemoved: Boolean,
        preferredFolderKey: FolderKey? = null,
    ) {
        val context = currentWorkspaceReadContext()
        if (context.projectLocation != project.toString() || !isCurrentWorkspace(context)) return
        val previous = _hierarchyState.value
        val folders = fileManager.listFolders(project)
        val contents = loadHierarchyFolderContents(folders, projectConfig.value, context)
        if (!isCurrentWorkspace(context)) return
        if (context.workspaceKind == WorkspaceKind.GENERAL) {
            val sources = fileManager.generalSources.load(project)
            if (!isCurrentWorkspace(context)) return
            _generalSourceState.value = sources
        } else _generalSourceState.value = null

        val requestedFolderKey = preferredKey?.folder
            ?: preferredFolderKey
            ?: previous.currentFolderKey
            ?: FolderKey.Base
        val folder = folders.find { it.key == requestedFolderKey }
            ?: folders.find { it.key == FolderKey.Base }
        val rememberedKey = folder?.let {
            folderFileSelectionMemory.preferred(
                folderKey = it.key,
                availableKeys = contents[it.key]?.files.orEmpty().map(ProjectFile::key),
            )
        }
        publishHierarchy(
            next = HierarchyUiState(
                folderList = folders,
                folderContents = contents,
                currentFolderKey = folder?.key,
                selectedFileKey = previous.selectedFileKey,
            ),
            preferredKey = preferredKey ?: rememberedKey,
            cancelRemoved = cancelRemoved,
        )
    }

    private fun applyFolderContent(
        folder: ProjectFolder,
        content: HierarchyFolderContent,
        preferredKey: FileKey?,
    ) {
        val previous = _hierarchyState.value
        publishHierarchy(
            next = previous.copy(
                folderContents = previous.folderContents + (folder.key to content),
                currentFolderKey = folder.key,
            ),
            preferredKey = preferredKey,
        )
    }

    /** 조회가 모두 끝난 뒤 목록과 선택을 한 번에 게시한다. 삭제 정리도 전체 하이라키 기준이다. */
    private fun publishHierarchy(
        next: HierarchyUiState,
        preferredKey: FileKey? = next.selectedFileKey,
        cancelRemoved: Boolean = false,
    ) {
        if (cancelRemoved) {
            val previousKeys = _hierarchyState.value.folderContents.values
                .flatMapTo(mutableSetOf()) { it.files.map(ProjectFile::key) }
            val freshKeys = next.folderContents.values
                .flatMapTo(mutableSetOf()) { it.files.map(ProjectFile::key) }
            // A vanished disk path does not authorize discarding an unresolved editor draft.
            val removedKeys = previousKeys - freshKeys - _documentConflicts.value.keys
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

        val files = next.fileList
        files.forEach {
            knownProjectFiles[it.key] = it
            editorSessionKey(it.key)
        }
        val requestedKey = preferredKey ?: next.selectedFileKey
        val selected = files.find { it.key == requestedKey } ?: files.firstOrNull()
        selected?.let { folderFileSelectionMemory.remember(it.key) }
        _hierarchyState.value = next.copy(selectedFileKey = selected?.key)
    }

    private fun selectedKeyFor(folderKey: FolderKey): FileKey? {
        if (_hierarchyState.value.currentFolderKey == folderKey) {
            return _hierarchyState.value.currentFile?.key
        }
        val availableKeys = _hierarchyState.value.folderContents[folderKey]
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
        val current = _hierarchyState.value
        publishHierarchy(
            next = current.copy(folderContents = current.folderContents + (folder.key to content)),
            preferredKey = if (current.currentFolderKey == folder.key) selectedNewKey else current.selectedFileKey,
        )
    }

    private fun clearProjectState(preserveBaseline: Boolean = false) {
        _documentConflicts.value = emptyMap()
        _generalSourceState.value = null
        committedFolderSettings = null
        incompleteCreation = null
        _canRetryCreation.value = false
        cancelCommitRequests()
        if (!preserveBaseline) {
            projectBaselineJob?.cancel()
            projectBaselineJob = null
            _projectBaselineUiState.value = ProjectBaselineUiState.Idle
        }
        saveCoordinator.cancelAll()
        pageLoadJobs.values.forEach(Job::cancel)
        pageLoadJobs.clear()
        _hierarchyState.value = HierarchyUiState()
        _pendingFolderDeletion.value = null
        _pendingFileTrash.value = null
        fileTrashPreparationInProgress = false
        _fileLoadStates.value = emptyMap()
        knownModified.clear()
        knownProjectFiles.clear()
        editorSessionKeys.clear()
        folderFileSelectionMemory.clear()
        _commitCreateUiState.value = CommitCreateUiState()
        _commitHistoryUiState.value = CommitHistoryUiState()
    }

    private suspend fun loadHierarchyFolderContents(
        folders: List<ProjectFolder>,
        config: ProjectConfig?,
        context: WorkspaceReadContext,
    ): Map<FolderKey, HierarchyFolderContent> = folders.associate { folder ->
        folder.key to loadFolderContent(folder, config, context)
    }

    private suspend fun loadFolderContent(
        folder: ProjectFolder,
        config: ProjectConfig? = projectConfig.value,
        context: WorkspaceReadContext = currentWorkspaceReadContext(),
    ): HierarchyFolderContent {
        val folderConfig = folderConfig(folder.key, config, context)
        val files = fileManager.listProjectFiles(folder)
        if (!folderConfig.isPlot) {
            if (!isCurrentWorkspace(context)) throw CancellationException("작업 공간이 변경되었습니다.")
            return HierarchyFolderContent(files = files.sortedFor(folderConfig))
        }
        val plotEntries = loadPlotEntries(folder, files, context).sortedForPlot()
        if (!isCurrentWorkspace(context)) throw CancellationException("작업 공간이 변경되었습니다.")
        return HierarchyFolderContent(
            files = plotEntries.map(PlotFileEntry::projectFile),
            plotEntries = plotEntries,
        )
    }

    private suspend fun loadPlotEntries(
        folder: ProjectFolder,
        files: List<ProjectFile>? = null,
        context: WorkspaceReadContext = currentWorkspaceReadContext(),
    ): List<PlotFileEntry> = (files ?: fileManager.listProjectFiles(folder)).map { projectFile ->
        val noteFile = loadedNote(projectFile.key)
            ?: try {
                fileManager.readMarkdown(projectFile.platformFile).also { loaded ->
                    if (isCurrentWorkspace(context)) putLoadedNote(projectFile.key, loaded)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (isCurrentWorkspace(context)) {
                    setFileLoadState(
                        projectFile.key,
                        FileLoadUiState.Error(error.message ?: "파일을 읽지 못했습니다."),
                    )
                }
                null
            }
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

    private fun currentWorkspaceReadContext(): WorkspaceReadContext {
        val snapshot = bookmarks.value
        return WorkspaceReadContext(
            projectLocation = snapshot.projectData?.toString(),
            workspaceKind = snapshot.workspaceKind,
            vaultLocation = snapshot.vaultData?.toString(),
            generation = workspaceUiGeneration,
        )
    }

    private fun isCurrentWorkspace(context: WorkspaceReadContext): Boolean {
        val current = bookmarks.value
        return activeProjectLocation == context.projectLocation &&
            context.generation == workspaceUiGeneration &&
            current.vaultData?.toString() == context.vaultLocation &&
            activeWorkspaceKind == context.workspaceKind &&
            current.projectData?.toString() == context.projectLocation &&
            current.workspaceKind == context.workspaceKind
    }

    private fun isCurrentProjectFile(projectFile: ProjectFile, context: WorkspaceReadContext): Boolean {
        if (!isCurrentWorkspace(context)) return false
        return knownProjectFiles[projectFile.key]?.platformFile?.toString() ==
            projectFile.platformFile.toString()
    }

    private fun isCurrentHierarchyFile(projectFile: ProjectFile, context: WorkspaceReadContext): Boolean {
        if (!isCurrentWorkspace(context)) return false
        val current = _hierarchyState.value.folderContents[projectFile.key.folder]
            ?.files
            ?.firstOrNull { it.key == projectFile.key }
        return current?.platformFile?.toString() == projectFile.platformFile.toString()
    }

    private fun folderConfig(folderKey: FolderKey): FolderConfig =
        folderConfig(folderKey, projectConfig.value, currentWorkspaceReadContext())

    private fun folderConfig(
        folderKey: FolderKey,
        config: ProjectConfig?,
        context: WorkspaceReadContext,
    ): FolderConfig =
        if (context.workspaceKind == WorkspaceKind.GENERAL) FolderConfig(type = FolderType.GENERAL)
        else config?.folders?.get(folderKey.relativePath)
            ?: if (folderKey == FolderKey.Base) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()

    private fun autoTagsFor(folderKey: FolderKey): List<String> {
        if (bookmarks.value.workspaceKind == WorkspaceKind.GENERAL) return emptyList()
        return projectConfig.value?.effectiveAutoTags(folderKey.relativePath).orEmpty()
    }

    private fun launchWorkspaceTransition(action: suspend () -> Unit) {
        if (workspaceInputBlocked || workspaceOperationBusy()) return
        val generation = workspaceUiGeneration
        val vault = bookmarks.value.vaultData?.toString()
        openingWorkspaceSelection = true
        navigationGate.newRequest()
        viewModelScope.launch {
            try {
                fileReconciliationMutex.withLock {
                    workspaceSaveCoordinator.runAfterFlush {
                        check(!workspaceOperationBusy()) { "진행 중인 작업이 끝난 뒤 다시 시도해 주세요." }
                        check(generation == workspaceUiGeneration && vault == bookmarks.value.vaultData?.toString()) {
                            "작업 공간이 변경되었습니다."
                        }
                        action()
                    }.onFailure { error ->
                        if (workspaceSaveCoordinator.lastErrorMessage.value == null) {
                            _workspaceTransitionError.value = error.message ?: "작업 공간을 전환하지 못했습니다."
                        }
                    }
                }
            } finally {
                openingWorkspaceSelection = false
            }
        }
    }

    /** 요청 대상은 호출 시 고정하고, 전환과 같은 순서로 저장 완료 후 변경한다. */
    private fun launchWorkspaceMutation(
        onFinished: () -> Unit = {},
        action: suspend (PlatformFile, WorkspaceReadContext) -> Unit,
    ) {
        if (workspaceInputBlocked) { onFinished(); return }
        val project = bookmarks.value.projectData ?: run { onFinished(); return }
        val context = currentWorkspaceReadContext()
        viewModelScope.launch {
            try { runWorkspaceMutation(project, context, action = action) } finally { onFinished() }
        }
    }

    private suspend fun runWorkspaceMutation(
        project: PlatformFile? = bookmarks.value.projectData,
        context: WorkspaceReadContext = currentWorkspaceReadContext(),
        reportErrors: Boolean = true,
        action: suspend (PlatformFile, WorkspaceReadContext) -> Unit,
    ) {
        if (workspaceInputBlocked || project == null) return
        fileReconciliationMutex.withLock {
            if (!isCurrentWorkspace(context)) return@withLock
            workspaceSaveCoordinator.runAfterFlush {
                if (isCurrentWorkspace(context)) action(project, context)
            }.onFailure { error ->
                if (reportErrors && isCurrentWorkspace(context) && workspaceSaveCoordinator.lastErrorMessage.value == null) {
                    _workspaceTransitionError.value = error.message ?: "파일 작업을 완료하지 못했습니다."
                }
            }
        }
    }

    private fun launchNavigation(action: suspend (isLatest: () -> Boolean) -> Unit) {
        if (workspaceInputBlocked) return
        val generation = workspaceUiGeneration
        val request = navigationGate.newRequest()
        viewModelScope.launch {
            // 파일 선택 bookmark와 순서 변경 rename이 서로의 오래된 경로를 덮어쓰지 않도록
            // 탐색도 외부 변경·rename과 같은 임계 구역에서 직렬화한다.
            fileReconciliationMutex.withLock {
                if (workspaceInputBlocked || generation != workspaceUiGeneration) return@withLock
                navigationGate.run(request) { latest ->
                    action { latest() && !workspaceInputBlocked && generation == workspaceUiGeneration }
                }
            }
        }
    }

    companion object {
        // Phase 2 폴링 주기. 활성(포커스) 상태에서만 동작.
        private const val POLL_INTERVAL_MS = 1500L
        private const val SAVE_DEBOUNCE_MS = 500L
    }
}

private data class WorkspaceReadContext(
    val projectLocation: String?,
    val workspaceKind: WorkspaceKind,
    val vaultLocation: String?,
    val generation: Long,
)

private fun String.toFileKeyOrNull(): FileKey? =
    runCatching { FileKey.of(this) }.getOrNull()

private data class PendingWrite(
    val projectFile: ProjectFile,
    val noteFile: NoteFile,
)

private data class OrderStateUpdate(
    val oldKey: FileKey,
    val projectFile: ProjectFile,
    val noteFile: NoteFile? = null,
)

data class FileTrashUiState(
    val file: ProjectFile,
    val busy: Boolean = false,
    val errorMessage: String? = null,
)

private fun ProjectFile.sameIdentityAs(other: ProjectFile): Boolean =
    key == other.key && platformFile.toString() == other.platformFile.toString()

data class HierarchyFolderContent(
    val files: List<ProjectFile> = emptyList(),
    val plotEntries: List<PlotFileEntry> = emptyList(),
)

/** 폴더별 목록과 선택 key만 저장한다. 편집 영역도 사이드바와 같은 snapshot에서 파생한다. */
data class HierarchyUiState(
    val folderList: List<ProjectFolder> = emptyList(),
    val folderContents: Map<FolderKey, HierarchyFolderContent> = emptyMap(),
    val currentFolderKey: FolderKey? = null,
    val selectedFileKey: FileKey? = null,
) {
    val currentFolder: ProjectFolder? = folderList.find { it.key == currentFolderKey }
    val fileList: List<ProjectFile> = folderContents[currentFolderKey]?.files.orEmpty()
    val plotFileEntries: List<PlotFileEntry> = folderContents[currentFolderKey]?.plotEntries.orEmpty()
    val currentIndex: Int = fileList.indexOfFirst { it.key == selectedFileKey }.coerceAtLeast(0)
    val currentFile: ProjectFile? = fileList.getOrNull(currentIndex)
}

sealed interface FileLoadUiState {
    data object Loading : FileLoadUiState
    data class Loaded(val noteFile: NoteFile) : FileLoadUiState
    data class Error(val message: String) : FileLoadUiState
}

data class CommitCreateUiState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val isCommitting: Boolean = false,
    val message: String = "",
    val preview: CommitPreview? = null,
    val diff: CommitDiffUiState? = null,
    val errorMessage: String? = null,
)

data class CommitHistoryUiState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val history: List<CommitHistoryEntry> = emptyList(),
    val workingPreview: CommitPreview? = null,
    val selectedCommitId: String? = null,
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
    val target: CommitRestoreTarget,
    val expectedWorkingTreeHash: String,
    val isRestoring: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface CommitRestoreTarget {
    data class Project(val entry: CommitHistoryEntry) : CommitRestoreTarget

    data class HeadSnapshot(val entry: CommitHistoryEntry) : CommitRestoreTarget

    data class HeadChanges(val entry: CommitHistoryEntry) : CommitRestoreTarget

    data class FileContent(
        val commitId: String,
        val change: CommitChange,
        val side: CommitFileSide,
    ) : CommitRestoreTarget

    data class File(
        val commitId: String,
        val change: CommitChange,
        val side: CommitFileSide,
    ) : CommitRestoreTarget
}

sealed interface ProjectBaselineUiState {
    val projectLocation: String?

    data object Idle : ProjectBaselineUiState {
        override val projectLocation: String? = null
    }

    data class Preparing(override val projectLocation: String) : ProjectBaselineUiState

    data class Ready(
        override val projectLocation: String,
        val skipped: Boolean = false,
    ) : ProjectBaselineUiState

    data class Error(
        override val projectLocation: String,
        val message: String,
    ) : ProjectBaselineUiState
}
