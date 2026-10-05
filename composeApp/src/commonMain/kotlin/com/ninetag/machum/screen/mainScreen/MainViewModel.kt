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
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
import com.ninetag.machum.entity.DocumentPropertyDefinitionChange
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.effectiveAutoTags
import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.WorkspaceLinkRewriteReceipt
import com.ninetag.machum.external.FileOrderRollbackException
import com.ninetag.machum.external.FileRenameRollbackException
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.FileCreationIncompleteException
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.GeneralSourceState
import com.ninetag.machum.external.GeneralSourceEntry
import com.ninetag.machum.external.GeneralSourcePlan
import com.ninetag.machum.external.GeneralSourceProperty
import com.ninetag.machum.external.DocumentPropertyProtectionPolicy
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.WorkspaceLinkIndexState
import com.ninetag.machum.external.WorkspaceLinkIndex
import com.ninetag.machum.external.WorkspaceLinkPath
import com.ninetag.machum.external.WorkspaceLinkDocument
import com.ninetag.machum.external.WorkspaceLinkResolution
import com.ninetag.machum.external.WorkspaceLinkTarget
import com.ninetag.machum.external.WorkspaceLinkResourceKind
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate
import com.ninetag.machum.markdown.state.MarkdownBlockReferenceCreation
import com.ninetag.machum.external.WorkspaceLinkSourceRange
import com.ninetag.machum.external.insertWorkspaceBlockId
import com.ninetag.machum.external.newWorkspaceBlockId
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionKind
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionSyntax
import com.ninetag.machum.markdown.state.MarkdownNavigationTarget
import com.ninetag.machum.markdown.state.MarkdownEmbedPreview
import com.ninetag.machum.markdown.state.extractMarkdownEmbed
import com.ninetag.machum.external.ProjectFileMoveAssignment
import com.ninetag.machum.external.ProjectFileMoveRollbackException
import com.ninetag.machum.external.ProjectFolder
import com.ninetag.machum.external.ProjectFolderDeletionPreview
import com.ninetag.machum.external.ProjectHierarchySnapshot
import com.ninetag.machum.external.PlotFileEntry
import com.ninetag.machum.external.PlotOrderAssignment
import com.ninetag.machum.external.IndexedPlot
import com.ninetag.machum.external.IndexedGeneralSource
import com.ninetag.machum.external.WorkspaceFileMetadata
import com.ninetag.machum.external.WorkspaceLoadDiagnostics
import com.ninetag.machum.external.isValidProjectFileTitle
import com.ninetag.machum.external.nextDefaultFileName
import com.ninetag.machum.external.nextPlotFileName
import com.ninetag.machum.external.defaultOrderPrefix
import com.ninetag.machum.external.defaultOrderTitle
import com.ninetag.machum.external.plotOrder
import com.ninetag.machum.external.plotTitle
import com.ninetag.machum.external.sortedForPlot
import com.ninetag.machum.external.sortedFor
import com.ninetag.machum.external.withManagedTagChanges
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import androidx.compose.ui.graphics.decodeToImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val GENERAL_PROPERTY_DEFINITION_SCOPE = "<general-workspace>"
private const val PROJECT_PLOT_SCAN_PARALLELISM = 4
private const val MAX_EMBED_IMAGE_BYTES = 20 * 1024 * 1024
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

data class PendingMarkdownNavigation(
    val fileKey: FileKey,
    val target: MarkdownNavigationTarget,
)

data class AmbiguousInternalLink(
    val sourceFile: ProjectFile,
    val candidates: List<WorkspaceLinkPath>,
)

class MainViewModel internal constructor(
    private val fileManager: FileManager,
    private val workspaceSaveCoordinator: WorkspaceSaveCoordinator,
    private val readWorkspaceName: (PlatformFile) -> String = { it.name },
    private val writeNote: suspend (PlatformFile, NoteFile) -> Unit,
) : ViewModel() {
    constructor(fileManager: FileManager, workspaceSaveCoordinator: WorkspaceSaveCoordinator) :
        this(fileManager, workspaceSaveCoordinator, writeNote = fileManager::writeMarkdown)

    val bookmarks: StateFlow<Bookmarks> = fileManager.bookmarks
    val projectConfig: StateFlow<ProjectConfig?> = fileManager.projectConfig
    val workspaceLinkIndexState: StateFlow<WorkspaceLinkIndexState> = fileManager.workspaceLinkIndexState

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
    private val pendingPropertyDefinitionSyncs = mutableMapOf<FileKey, PendingPropertyDefinitionSync>()
    private var propertyDefinitionRevision = 0L
    private val latestPropertyTypeRevision = mutableMapOf<String, Long>()
    private val latestPropertyMembershipRevision = mutableMapOf<Pair<String, String>, Long>()
    private val _propertyDefinitionSyncUiState = MutableStateFlow<Map<FileKey, PropertyDefinitionSyncUiState>>(emptyMap())
    val propertyDefinitionSyncUiState = _propertyDefinitionSyncUiState.asStateFlow()

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
    private val _markdownNavigation = MutableStateFlow<PendingMarkdownNavigation?>(null)
    val markdownNavigation: StateFlow<PendingMarkdownNavigation?> = _markdownNavigation.asStateFlow()
    private var nextMarkdownNavigationId = 0L
    private val _ambiguousInternalLink = MutableStateFlow<AmbiguousInternalLink?>(null)
    val ambiguousInternalLink: StateFlow<AmbiguousInternalLink?> = _ambiguousInternalLink.asStateFlow()
    private val _generalLinkReturnTarget = MutableStateFlow<WorkspaceLinkPath?>(null)
    val generalLinkReturnTarget: StateFlow<WorkspaceLinkPath?> = _generalLinkReturnTarget.asStateFlow()
    private val _projectLinkReturnTarget = MutableStateFlow<WorkspaceLinkPath?>(null)
    val projectLinkReturnTarget: StateFlow<WorkspaceLinkPath?> = _projectLinkReturnTarget.asStateFlow()

    // 외부(옵시디언 등) 변경 감지용 — 파일별로 마지막으로 "인지한" 수정 시각.
    // 앱 자신의 쓰기 직후에도 갱신하여, 폴링이 자기 쓰기를 외부 변경으로 오인하지 않게 한다.
    private val knownModified = mutableMapOf<FileKey, Long>()
    private val knownProjectFiles = mutableMapOf<FileKey, ProjectFile>()
    private val activeFileMoveInputs = mutableMapOf<FileKey, FileMoveInputBuffer>()
    // 파일명은 바뀌어도 같은 편집기 composition/session을 유지하기 위한 런타임 정체성이다.
    private val editorSessionKeys = mutableMapOf<FileKey, String>()
    private var nextEditorSessionId = 0L
    private val fileReconciliationMutex = Mutex()
    private val blockReferenceCreations = mutableSetOf<WorkspaceLinkPath>()
    private val folderFileSelectionMemory = FolderFileSelectionMemory()
    private val navigationGate = LatestNavigationGate()
    private val pageLoadJobs = mutableMapOf<FileKey, Job>()
    private var generalSourceRefreshJob: Job? = null
    private var workspaceListRefreshJob: Job? = null
    private var workspaceListRefreshVault: String? = null
    private var workspaceListRefreshPendingVault: String? = null
    private var workspaceLinkIndexRefreshJob: Job? = null
    private var workspaceLinkIndexRefreshVault: String? = null
    private var workspaceLinkIndexRefreshPendingVault: String? = null
    private var workspaceLinkIndexRefreshGeneration = 0L
    private var pendingGeneralSourceRefresh: PendingGeneralSourceRefresh? = null

    private var initialHierarchySnapshotAttempt: WorkspaceReadContext? = null
    private var appliedHierarchyConfig: ProjectConfig? = null

    private val saveCoordinator = DebouncedSaveCoordinator<FileKey, PendingWrite>(
        scope = viewModelScope,
        debounceMillis = SAVE_DEBOUNCE_MS,
        onSaveFailure = { fileKey, error ->
            workspaceSaveCoordinator.reportAutoSaveFailure(fileKey.relativePath, error)
        },
    ) { fileKey, pendingWrite ->
        val saveOwner = currentCoroutineContext().job
        WorkspaceLoadDiagnostics.measure("document-save", "autosave") {
        check(fileKey !in _documentConflicts.value) { _documentConflicts.value[fileKey].orEmpty() }
        check(isCurrentProjectFile(pendingWrite.projectFile, pendingWrite.context)) { "문서 위치가 변경되었습니다." }
        val file = pendingWrite.projectFile.platformFile
        val disk = fileManager.readMarkdown(file)
        if (disk.inject() != pendingWrite.expectedDisk.inject()) {
            withContext(NonCancellable) {
                // 기존 요청을 먼저 소비해야 fresh 게시 뒤 시작된 새 session 입력을 지우지 않는다.
                discardPendingWrite(fileKey)
                acceptExternalDocument(
                    fileKey = fileKey,
                    file = pendingWrite.projectFile,
                    fresh = disk,
                    context = pendingWrite.context,
                    cancelPending = false,
                )
            }
            return@measure
        }
        withContext(NonCancellable) {
            writeNote(file, pendingWrite.noteFile)
            try {
                // 자기 쓰기 mtime 기록 → 폴링이 외부 변경으로 오인하지 않도록
                val modifiedAt = fileManager.lastModified(file)
                if (isCurrentWorkspace(pendingWrite.context)) {
                    knownModified[fileKey] = modifiedAt ?: 0L
                }
                updateWorkspaceMetadataIndex(
                    pendingWrite.projectFile,
                    pendingWrite.noteFile,
                    modifiedAt,
                    pendingWrite.context,
                )
            } finally {
                // write 뒤의 mtime/index 대기 중 들어온 후속 입력도 방금 쓴 문서를 기준으로 삼는다.
                rebasePendingWriteAfterSave(fileKey, pendingWrite, saveOwner)
            }
        }
        }
    }

    private fun discardPendingWrite(fileKey: FileKey) {
        saveCoordinator.cancel(fileKey)
    }

    private suspend fun rebasePendingWriteAfterSave(fileKey: FileKey, completed: PendingWrite, saveOwner: Job) {
        saveCoordinator.rebaseReplacement(fileKey, saveOwner) { replacement ->
            replacement.copy(expectedDisk = completed.noteFile)
        }
    }

    private val folderSettingsService = FolderSettingsService(fileManager)
    private val commitSessionCoordinator = CommitSessionCoordinator(
        scope = viewModelScope,
        service = ProjectCommitService(fileManager),
        reconciliationMutex = fileReconciliationMutex,
        saveCoordinator = saveCoordinator,
        workspace = {
            CommitWorkspaceContext(
                project = bookmarks.value.projectData,
                kind = bookmarks.value.workspaceKind,
                context = currentWorkspaceReadContext(),
                inputBlocked = workspaceInputBlocked,
                pendingPropertyDefinitionSync = hasPendingPropertyDefinitionSync(),
            )
        },
        isCurrentWorkspace = ::isCurrentWorkspace,
        restoreSelection = {
            CommitRestoreSelection(
                fileKey = _hierarchyState.value.currentFile?.key,
                folderKey = _hierarchyState.value.currentFolderKey,
            )
        },
        applyProjectRestore = { project, selection ->
            invalidateAllEditorRuntimeForRestore()
            fileManager.reconcileWorkspaceAfterRestore(project)
            refreshFoldersAndFiles(
                project = project,
                preferredKey = selection.fileKey,
                preferredFolderKey = selection.folderKey,
                cancelRemoved = false,
            )
        },
        applyFileRestore = { project, result, selection ->
            reconcileSingleFileRestore(
                project = project,
                result = result,
                selectedFileKey = selection.fileKey,
                selectedFolderKey = selection.folderKey,
            )
        },
        reloadSelectedFile = ::reloadSelectedFileAfterRestore,
    )

    val commitCreateUiState: StateFlow<CommitCreateUiState> = commitSessionCoordinator.createUiState
    val commitHistoryUiState: StateFlow<CommitHistoryUiState> = commitSessionCoordinator.historyUiState
    val projectBaselineUiState: StateFlow<ProjectBaselineUiState> = commitSessionCoordinator.baselineUiState

    val workspaceSaveError: StateFlow<String?> = workspaceSaveCoordinator.lastErrorMessage
    private val workspaceSessionCoordinator = WorkspaceSessionCoordinator(
        scope = viewModelScope,
        fileManager = fileManager,
        reconciliationMutex = fileReconciliationMutex,
        saveCoordinator = workspaceSaveCoordinator,
        operationBusy = ::workspaceOperationBusy,
        invalidateNavigation = { navigationGate.newRequest() },
        clearProjectState = { clearProjectState() },
        checkExternalChanges = { refreshHierarchy ->
            checkExternalChanges(refreshHierarchy = refreshHierarchy)
        },
    )
    val workspaceTransitionError: StateFlow<String?> = workspaceSessionCoordinator.transitionError
    val workspaceSelectionVisible: StateFlow<Boolean> = workspaceSessionCoordinator.workspaceSelectionVisible
    val vaultSelectionVisible: StateFlow<Boolean> = workspaceSessionCoordinator.vaultSelectionVisible
    val workspaceSelectionPending: StateFlow<Boolean> = workspaceSessionCoordinator.workspaceSelectionPending

    private val workspaceInputBlocked: Boolean
        get() = workspaceSessionCoordinator.inputBlocked

    private fun workspaceOperationBusy(): Boolean =
        commitSessionCoordinator.isBusy() || hasPendingPropertyDefinitionSync()

    private fun hasPendingPropertyDefinitionSync(): Boolean =
        pendingPropertyDefinitionSyncs.isNotEmpty()

    /** Leave editing only after writes settle; bookmarks remain ordinary reopening data. */
    fun openWorkspaceSelection() = workspaceSessionCoordinator.openWorkspaceSelection()

    /** Workspace selection always goes up to Vault selection, never to the old editor. */
    fun openVaultSelection() = workspaceSessionCoordinator.openVaultSelection()

    /** Called only by a successful native selection or new-Vault creation. */
    fun completeVaultSelection() = workspaceSessionCoordinator.completeVaultSelection()

    /** Call only after the selected directory has opened successfully, including setup confirmation. */
    suspend fun completeWorkspaceSelection() {
        val snapshot = bookmarks.value
        val project = snapshot.projectData ?: return
        if (fileManager.workspaceOpenRequest.value != null) return
        workspaceSessionCoordinator.beginWorkspaceCompletion(project.toString(), snapshot.workspaceKind)
        val preferred = snapshot.fileRelativePath?.let { runCatching { FileKey.of(it) }.getOrNull() }
        refreshFoldersAndFiles(
            project,
            preferred,
            cancelRemoved = false,
            useInitialProjectIndexSnapshot = true,
        )
        workspaceSessionCoordinator.finishWorkspaceCompletion()
        _generalLinkReturnTarget.value = null
    }

    /** Selection commands share the save/rename fence; captured actions cannot run in a different Vault. */
    suspend fun <T> runWorkspaceSelectionAction(action: suspend () -> T): Result<T> =
        workspaceSessionCoordinator.runSelectionAction(action)

    /** Classification is owned by workspace selection, including sidebar requests. */
    suspend fun confirmWorkspaceOpen(kind: WorkspaceKind): Result<Unit> =
        workspaceSessionCoordinator.confirmWorkspaceOpen(kind, ::completeWorkspaceSelection)
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
                try {
                    fileReconciliationMutex.withLock {
                        var workspaceListsNeedRefresh = false
                        val vault = bookmarks.vaultData
                        if (vault == null) {
                            workspaceListRefreshJob?.cancel()
                            workspaceListRefreshJob = null
                            workspaceListRefreshVault = null
                            workspaceListRefreshPendingVault = null
                            workspaceLinkIndexRefreshGeneration += 1
                            workspaceLinkIndexRefreshJob?.cancel()
                            workspaceLinkIndexRefreshJob = null
                            workspaceLinkIndexRefreshVault = null
                            workspaceLinkIndexRefreshPendingVault = null
                            _projectList.value = emptyList()
                            _generalFolderList.value = emptyList()
                            workspaceSessionCoordinator.updateActiveVault(null)
                        } else {
                            val vaultLocation = vault.toString()
                            if (workspaceSessionCoordinator.updateActiveVault(vaultLocation)) {
                                _projectList.value = emptyList()
                                _generalFolderList.value = emptyList()
                                workspaceListsNeedRefresh = true
                                scheduleWorkspaceLinkIndexRefresh(vault)
                            }
                        }

                        if (workspaceSelectionVisible.value) {
                            if (workspaceListsNeedRefresh) vault?.let(::scheduleWorkspaceListRefresh)
                            return@withLock
                        }
                        val project = bookmarks.projectData
                        if (project == null) {
                            if (workspaceSessionCoordinator.hasActiveProject()) {
                                clearProjectState()
                                workspaceSessionCoordinator.clearActiveWorkspace()
                            }
                            if (workspaceListsNeedRefresh) vault?.let(::scheduleWorkspaceListRefresh)
                            return@withLock
                        }
                        val projectLocation = project.toString()
                        val projectChanged = workspaceSessionCoordinator.isWorkspaceChanged(
                            projectLocation,
                            bookmarks.workspaceKind,
                        )
                        if (projectChanged) {
                            WorkspaceLoadDiagnostics.event(
                                "workspace-selected",
                                "kind=${bookmarks.workspaceKind}|generation=${currentWorkspaceReadContext().generation}",
                            )
                            // The screen can request this project's baseline before this collector
                            // finishes its initial IO. Keep that request: cancelling it back to Idle
                            // can be conflated by Compose and leave the loading gate without a job.
                            val preserveBaseline = bookmarks.workspaceKind == WorkspaceKind.PROJECT &&
                                projectBaselineUiState.value.projectLocation == projectLocation
                            clearProjectState(preserveBaseline = preserveBaseline)
                            workspaceSessionCoordinator.activateWorkspace(projectLocation, bookmarks.workspaceKind)
                            workspaceListsNeedRefresh = true
                        }
                        if (projectChanged || _hierarchyState.value.folderList.isEmpty()) {
                            val preferredKey = bookmarks.fileRelativePath
                                ?.let { runCatching { FileKey.of(it) }.getOrNull() }
                            refreshFoldersAndFiles(
                                project,
                                preferredKey,
                                cancelRemoved = false,
                                useInitialProjectIndexSnapshot = true,
                            )
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
                        if (workspaceListsNeedRefresh) vault?.let(::scheduleWorkspaceListRefresh)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    WorkspaceLoadDiagnostics.event(
                        "workspace-collector.failed",
                        "error=${error::class.simpleName}",
                    )
                    workspaceSessionCoordinator.reportError(
                        error.message ?: "파일 목록을 불러오지 못했습니다. 다시 시도해 주세요.",
                    )
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
                    // General folder rows always use the fixed GENERAL type; their transient
                    // ProjectConfig only supports file operations and must not trigger another
                    // full SAF directory enumeration after the workspace hierarchy is published.
                    if (context.workspaceKind == WorkspaceKind.GENERAL) return@withLock
                    val current = _hierarchyState.value
                    if (current.folderList.isNotEmpty() && appliedHierarchyConfig == config) return@withLock
                    val contents = loadHierarchyFolderContents(current.folderList, config, context)
                    if (!isCurrentWorkspace(context)) return@withLock
                    publishHierarchy(current.copy(folderContents = contents))
                    appliedHierarchyConfig = config
                }
            }
        }

    }

    /** 앱/창 포커스 상태 전달 (MainScreen 의 LocalWindowInfo.isWindowFocused) */
    fun setActive(active: Boolean) = workspaceSessionCoordinator.setActive(active)

    /** 커밋 이력이 없는 관리 Project를 편집하기 전에 복원 가능한 최초 기준점을 만든다. */
    fun ensureInitialProjectBaseline() = commitSessionCoordinator.ensureInitialBaseline()

    /** 저장소 제약이 있는 경우 사용자가 위험을 인지하고 기준점 없이 편집을 계속한다. */
    fun skipInitialProjectBaseline() = commitSessionCoordinator.skipInitialBaseline()

    fun dismissWorkspaceTransitionError() {
        workspaceSaveCoordinator.clearError()
        workspaceSessionCoordinator.clearError()
    }

    fun openCommitDialog() = commitSessionCoordinator.openChanges()

    fun dismissCommitDialog() = commitSessionCoordinator.dismissWorkspace()

    fun dismissCommitWorkspace() = commitSessionCoordinator.dismissWorkspace()

    fun updateCommitMessage(message: String) = commitSessionCoordinator.updateMessage(message)

    fun openCommitDiff(change: CommitChange) = commitSessionCoordinator.openChangesDiff(change)

    fun closeCommitDiff() = commitSessionCoordinator.closeChangesDiff()

    fun openCommitHistory() = commitSessionCoordinator.openHistory()

    fun retryCommitHistory() = commitSessionCoordinator.retryHistory()

    fun selectCommitHistoryEntry(commitId: String) = commitSessionCoordinator.selectHistoryEntry(commitId)

    fun editCommitHistoryMessage(commitId: String) = commitSessionCoordinator.openMessageEdit(commitId)

    fun updateEditedCommitMessage(message: String) = commitSessionCoordinator.updateEditedMessage(message)

    fun saveEditedCommitMessage() = commitSessionCoordinator.saveEditedMessage()

    fun dismissCommitMessageEdit() = commitSessionCoordinator.dismissMessageEdit()

    fun openCommitHistoryDiff(commitId: String, change: CommitChange) =
        commitSessionCoordinator.openHistoryDiff(commitId, change)

    fun navigateBackFromCommitHistory() = commitSessionCoordinator.navigateBackFromHistory()

    fun requestProjectRestore(entry: CommitHistoryEntry) = commitSessionCoordinator.requestProjectRestore(entry)

    fun requestHeadRevert(entry: CommitHistoryEntry) = commitSessionCoordinator.requestHeadRevert(entry)

    fun requestFileContentRestore(change: CommitChange, side: CommitFileSide) =
        commitSessionCoordinator.requestFileRestore(change, side, contentOnly = true)

    fun requestFileRestore(change: CommitChange, side: CommitFileSide) =
        commitSessionCoordinator.requestFileRestore(change, side, contentOnly = false)

    fun dismissCommitRestore() = commitSessionCoordinator.dismissRestore()

    fun confirmCommitRestore() = commitSessionCoordinator.confirmRestore()

    fun createCommit(message: String) = commitSessionCoordinator.createCommit(message)
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
        fileManager.reconcileWorkspaceAfterRestore(project)

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
        knownModified[selected.key] = fileManager.lastModified(selected.platformFile) ?: 0L
    }

    fun refreshProjectList() {
        bookmarks.value.vaultData?.let(::scheduleWorkspaceListRefresh)
    }

    private suspend fun refreshWorkspaceLists(vault: PlatformFile) {
        val trace = WorkspaceLoadDiagnostics.begin("workspace-list.publish")
        try {
            val locations = fileManager.listWorkspaceDirectories(vault)
            if (bookmarks.value.vaultData?.toString() != vault.toString()) {
                trace.complete("discarded=true")
                return
            }
            _projectList.value = locations.projectChoices
            _generalFolderList.value = locations.generalFolders
            trace.complete(
                "projects=${locations.projectChoices.size}|general=${locations.generalFolders.size}",
            )
        } catch (error: Exception) {
            trace.fail(error)
            throw error
        }
    }

    private fun scheduleWorkspaceListRefresh(vault: PlatformFile) {
        val identity = vault.toString()
        if (workspaceListRefreshJob?.isActive == true && workspaceListRefreshVault == identity) {
            workspaceListRefreshPendingVault = identity
            return
        }
        workspaceListRefreshJob?.cancel()
        workspaceListRefreshVault = identity
        workspaceListRefreshPendingVault = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                refreshWorkspaceLists(vault)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (bookmarks.value.vaultData?.toString() == identity) {
                    workspaceSessionCoordinator.reportError(
                        error.message ?: "작업 공간 목록을 불러오지 못했습니다.",
                    )
                }
            } finally {
                val running = currentCoroutineContext().job
                if (workspaceListRefreshJob === running) {
                    val rerun = workspaceListRefreshPendingVault == identity &&
                        bookmarks.value.vaultData?.toString() == identity
                    workspaceListRefreshPendingVault = null
                    workspaceListRefreshJob = null
                    workspaceListRefreshVault = null
                    if (rerun) scheduleWorkspaceListRefresh(vault)
                }
            }
        }
        workspaceListRefreshJob = job
        job.start()
    }

    fun selectProject(project: PlatformFile) {
        workspaceSessionCoordinator.launchTransition {
            fileManager.requestOpenWorkspace(project)
            if (fileManager.workspaceOpenRequest.value != null) {
                workspaceSessionCoordinator.ensureWorkspaceSelectionVisible()
            } else {
                _generalLinkReturnTarget.value = null
            }
        }
    }

    fun returnToLastProject() {
        workspaceSessionCoordinator.launchTransition {
            val trace = WorkspaceLoadDiagnostics.begin("return-to-project")
            try {
                fileManager.returnToLastProject()
                if (bookmarks.value.projectData == null) {
                    workspaceSessionCoordinator.ensureWorkspaceSelectionVisible()
                } else {
                    _generalLinkReturnTarget.value = null
                }
                trace.complete()
            } catch (error: Throwable) {
                trace.fail(error)
                throw error
            }
        }
    }

    fun navigateToProjectRoot() {
        selectFolder(FolderKey.Base)
    }

    fun selectFolder(folderKey: FolderKey) {
        _projectLinkReturnTarget.value = null
        launchNavigation { isLatest ->
            val folder = _hierarchyState.value.folderList.find { it.key == folderKey } ?: return@launchNavigation
            // Focus refresh owns external inventory discovery; navigation reuses its snapshot.
            val cached = _hierarchyState.value.folderContents[folder.key]
            cached?.let { content ->
                if (!isLatest()) return@launchNavigation
                applyFolderContent(
                    folder,
                    content,
                    preferredKey = folderFileSelectionMemory.preferred(
                        folderKey = folder.key,
                        availableKeys = content.files.map(ProjectFile::key),
                    ),
                )
            }

            if (cached != null) {
                cached.files.getOrNull(_hierarchyState.value.currentIndex)?.let { fileManager.pickFile(it) }
                return@launchNavigation
            }
            val content = try {
                loadFolderContent(folder)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (isLatest()) {
                    workspaceSessionCoordinator.reportError(error.message ?: "파일 목록을 새로 고치지 못했습니다.")
                }
                return@launchNavigation
            }
            if (!isLatest()) return@launchNavigation
            run {
                applyFolderContent(
                    folder,
                    content,
                    preferredKey = folderFileSelectionMemory.preferred(
                        folderKey = folder.key,
                        availableKeys = content.files.map(ProjectFile::key),
                    ),
                )
            }
            content.files.getOrNull(_hierarchyState.value.currentIndex)?.let { selected ->
                fileManager.pickFile(selected)
            }
        }
    }

    fun selectFile(fileKey: FileKey) {
        if (_hierarchyState.value.selectedFileKey != fileKey) {
            _projectLinkReturnTarget.value = null
        }
        selectFileInternal(fileKey)
    }

    private fun selectFileInternal(fileKey: FileKey, onSelected: () -> Unit = {}) {
        launchNavigation { isLatest ->
            val folder = _hierarchyState.value.folderList.find { it.key == fileKey.folder }
                ?: return@launchNavigation
            val content = _hierarchyState.value.folderContents[folder.key]
                ?.takeIf { cached -> cached.files.any { it.key == fileKey } }
                ?: loadFolderContent(folder)
            val file = content.files.find { it.key == fileKey } ?: return@launchNavigation
            if (!fileManager.workspaceExists(file.platformFile)) return@launchNavigation
            if (!isLatest()) return@launchNavigation
            fileManager.pickFile(file)
            if (!isLatest()) return@launchNavigation
            applyFolderContent(folder, content, preferredKey = fileKey)
            folderFileSelectionMemory.remember(file.key)
            loadPage(file)
            if (isLatest()) onSelected()
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
                workspaceSessionCoordinator.clearError()
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
                val workspace = bookmarks.value.projectData
                    ?.takeIf { it.toString() == context.projectLocation }
                    ?: return
                val modifiedAt = fileManager.lastModified(created.platformFile)
                knownModified[created.key] = modifiedAt ?: 0L
                updateWorkspaceMetadataIndex(created, noteFile, modifiedAt, context)
                updateGeneralSourceEntry(created, noteFile, context)
                if (isCurrentWorkspace(context)) {
                    scheduleGeneralSourceRefresh(workspace, context, currentHierarchyFiles(), refreshMetadata = false)
                }
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
        if (hasPendingPropertyDefinitionSync()) return
        if (_creationInProgress.value) return
        if (incompleteCreation != null) {
            workspaceSessionCoordinator.reportError(
                "이전 파일의 설정 기록이 완료되지 않았습니다. 남은 기록 재시도를 사용해 주세요. 파일을 직접 확인한 뒤 작업 공간 선택으로 나갔다 다시 열면 새 파일을 생성할 수 있습니다.",
            )
            return
        }
        _creationInProgress.value = true
        launchWorkspaceMutation(onFinished = { _creationInProgress.value = false }) { _, context ->
            if (!request.matchesWorkspace(context.projectLocation, context.workspaceKind)) return@launchWorkspaceMutation
            if (hasPendingPropertyDefinitionSync()) {
                return@launchWorkspaceMutation
            }
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
        if (workspaceInputBlocked || hasPendingPropertyDefinitionSync()) return false
        val context = currentWorkspaceReadContext()
        return withMutationIndexGate(context, false) withLock@ {
            if (workspaceInputBlocked || !isCurrentWorkspace(context) || hasPendingPropertyDefinitionSync()) {
                return@withLock false
            }
            val folder = _hierarchyState.value.folderList.find { it.key == folderKey } ?: return@withLock false
            val config = folderConfig(folderKey)
            if (config.type != FolderType.DEFAULT || config.isPlot) return@withLock false

            try {
                if (_documentConflicts.value.isNotEmpty()) return@withLock false
                saveCoordinator.flushAll()
                if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock false
                val selectedOldKey = selectedKeyFor(folderKey)
                saveCoordinator.withWritesPaused {
                    if (!isCurrentWorkspace(context)) return@withWritesPaused false
                    withContext(NonCancellable) {
                        val result = fileManager.applyDefaultOrderWithReferences(folder, orderedFileKeys)
                            ?: return@withContext restoreOrderHandles(orderedFileKeys)
                        reconcileOrderUpdates(
                            folder = folder,
                            updates = result.updates.map { update ->
                                OrderStateUpdate(oldKey = update.oldKey, projectFile = update.projectFile)
                            },
                            selectedOldKey = selectedOldKey,
                            references = result.references,
                        )
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: FileOrderRollbackException) {
                quarantinePathChanges(error.affectedPaths, error)
                false
            } catch (error: Exception) {
                false
            }
        }
    }

    suspend fun savePlotOrder(
        folderKey: FolderKey,
        assignments: List<PlotOrderAssignment>,
    ): Boolean {
        if (workspaceInputBlocked || hasPendingPropertyDefinitionSync()) return false
        val context = currentWorkspaceReadContext()
        return withMutationIndexGate(context, false) withLock@ {
            if (workspaceInputBlocked || !isCurrentWorkspace(context) || hasPendingPropertyDefinitionSync()) {
                return@withLock false
            }
            val folder = _hierarchyState.value.folderList.find { it.key == folderKey } ?: return@withLock false
            if (!folderConfig(folderKey).isPlot) return@withLock false

            try {
                val assignmentKeys = assignments.mapTo(mutableSetOf()) { it.fileKey }
                if (_documentConflicts.value.isNotEmpty()) return@withLock false
                saveCoordinator.flushAll()
                if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock false
                val selectedOldKey = selectedKeyFor(folderKey)
                saveCoordinator.withWritesPaused {
                    if (!isCurrentWorkspace(context)) return@withWritesPaused false
                    withContext(NonCancellable) {
                        val result = fileManager.applyPlotOrderWithReferences(folder, assignments)
                            ?: return@withContext restoreOrderHandles(assignmentKeys.toList())
                        reconcileOrderUpdates(
                            folder = folder,
                            updates = result.updates.map { update ->
                                OrderStateUpdate(
                                    oldKey = update.oldKey,
                                    projectFile = update.projectFile,
                                    noteFile = update.noteFile,
                                )
                            },
                            selectedOldKey = selectedOldKey,
                            references = result.references,
                        )
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: FileOrderRollbackException) {
                quarantinePathChanges(error.affectedPaths, error)
                false
            } catch (error: Exception) {
                false
            }
        }
    }

    fun createDirectory(name: String = "무제", folderConfig: FolderConfig = FolderConfig(type = FolderType.GENERAL)) {
        if (_creationInProgress.value || hasPendingPropertyDefinitionSync()) return
        _creationInProgress.value = true
        launchWorkspaceMutation(onFinished = { _creationInProgress.value = false }) { project, _ ->
            if (hasPendingPropertyDefinitionSync()) return@launchWorkspaceMutation
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
        if (workspaceInputBlocked || hasPendingPropertyDefinitionSync()) return
        val context = currentWorkspaceReadContext()
        viewModelScope.launch {
            fileReconciliationMutex.withLock {
                if (!isCurrentWorkspace(context) || hasPendingPropertyDefinitionSync()) return@withLock
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
        if (hasPendingPropertyDefinitionSync()) return
        launchWorkspaceMutation { project, _ ->
            if (_pendingFolderDeletion.value != requested) return@launchWorkspaceMutation
            if (hasPendingPropertyDefinitionSync()) return@launchWorkspaceMutation
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
            val refreshFailure = runCatching {
                refreshFoldersAndFiles(
                    project = project,
                    preferredKey = null,
                    cancelRemoved = true,
                    preferredFolderKey = previousFolderKey
                        ?.takeUnless { it == result.folderKey }
                        ?: FolderKey.Base,
                )
            }.exceptionOrNull()
            val messages = listOfNotNull(
                result.cleanupWarning,
                refreshFailure?.let {
                    "폴더는 휴지통으로 이동했지만 목록을 새로 고치지 못했습니다. ${it.message.orEmpty()}"
                },
            )
            if (messages.isNotEmpty()) workspaceSessionCoordinator.reportError(messages.joinToString("\n"))
        }
    }

    /** Opens an existing Markdown document referenced by a wiki/Markdown link. */
    fun openInternalDocumentLink(sourceFile: ProjectFile, target: String) {
        val source = workspaceLinkPath(sourceFile)
        val index = currentWorkspaceLinkIndex()
        val reference = source?.let { parseWorkspaceLinkReference(it, target) }
        val resolution = if (source != null && index != null && reference != null) {
            index.resolve(source, reference)
        } else null
        if (resolution is WorkspaceLinkResolution.Ambiguous) {
            _ambiguousInternalLink.value = AmbiguousInternalLink(
                sourceFile = sourceFile,
                candidates = resolution.candidates.map { it.path },
            )
            return
        }
        val resolved = resolution as? WorkspaceLinkResolution.Resolved
        if (resolved?.document?.resourceKind == WorkspaceLinkResourceKind.ATTACHMENT) {
            viewModelScope.launch {
                if (!fileManager.openWorkspaceAttachment(resolved.document.path)) {
                    workspaceSessionCoordinator.reportError("연결된 파일을 열 수 없습니다.")
                }
            }
            return
        }
        if (resolved != null && resolved.document.resourceKind == WorkspaceLinkResourceKind.MARKDOWN) {
            if (resolved.document.path.workspaceName != source?.workspaceName ||
                resolved.document.path.workspaceKind != source.workspaceKind
            ) {
                openCrossWorkspaceDocument(
                    resolved,
                    generalReturnTarget = source?.takeIf {
                        it.workspaceKind == WorkspaceKind.PROJECT &&
                            resolved.document.path.workspaceKind == WorkspaceKind.GENERAL
                    },
                )
                return
            }
            val key = runCatching { FileKey.of(resolved.document.path.relativePath) }.getOrNull()
            val file = key?.let { wanted ->
                _hierarchyState.value.folderContents.values
                    .asSequence()
                    .flatMap { it.files.asSequence() }
                    .firstOrNull { it.key == wanted }
            }
            if (file != null) {
                resolved.heading?.let { heading ->
                    _markdownNavigation.value = PendingMarkdownNavigation(
                        fileKey = file.key,
                        target = MarkdownNavigationTarget(++nextMarkdownNavigationId, headingPath = heading.path),
                    )
                } ?: resolved.block?.let { block ->
                    _markdownNavigation.value = PendingMarkdownNavigation(
                        fileKey = file.key,
                        target = MarkdownNavigationTarget(++nextMarkdownNavigationId, blockId = block.id),
                    )
                }
                if (file.key != sourceFile.key) {
                    selectInternalLinkFile(sourceFile, file.key)
                }
            }
            return
        }
        if (index != null) return
        // Keep the pre-index local resolver available while an index is still being built.
        resolveInternalDocumentLink(_hierarchyState.value, target)?.let { key ->
            selectInternalLinkFile(sourceFile, key)
        }
    }

    fun consumeMarkdownNavigation(id: Long) {
        if (_markdownNavigation.value?.target?.id == id) _markdownNavigation.value = null
    }

    fun dismissAmbiguousInternalLink() {
        _ambiguousInternalLink.value = null
    }

    fun chooseAmbiguousInternalLink(path: WorkspaceLinkPath) {
        val ambiguous = _ambiguousInternalLink.value
        _ambiguousInternalLink.value = null
        val current = bookmarks.value
        if (path.workspaceName == current.projectData?.name && path.workspaceKind == current.workspaceKind) {
            runCatching { FileKey.of(path.relativePath) }.getOrNull()?.let { key ->
                ambiguous?.sourceFile?.let { selectInternalLinkFile(it, key) }
            }
        } else {
            currentWorkspaceLinkIndex()?.documents
                ?.firstOrNull { it.path == path && it.resourceKind == WorkspaceLinkResourceKind.MARKDOWN }
                ?.let { document ->
                    val source = ambiguous?.sourceFile?.let(::workspaceLinkPath)
                    openCrossWorkspaceDocument(
                        WorkspaceLinkResolution.Resolved(document),
                        generalReturnTarget = source.takeIf {
                            it?.workspaceKind == WorkspaceKind.PROJECT && path.workspaceKind == WorkspaceKind.GENERAL
                        },
                    )
                }
        }
    }

    fun returnFromGeneralLink() {
        val path = _generalLinkReturnTarget.value ?: return
        val document = currentWorkspaceLinkIndex()?.documents?.firstOrNull {
            it.path == path && it.resourceKind == WorkspaceLinkResourceKind.MARKDOWN
        }
        if (document == null) {
            workspaceSessionCoordinator.reportError("돌아갈 원본 문서를 찾을 수 없습니다.")
            return
        }
        openCrossWorkspaceDocument(
            WorkspaceLinkResolution.Resolved(document),
            clearGeneralReturnOnSuccess = true,
        )
    }

    fun returnFromProjectLink() {
        val path = _projectLinkReturnTarget.value ?: return
        if (path.workspaceKind != WorkspaceKind.PROJECT || path.workspaceName != bookmarks.value.projectData?.name) {
            _projectLinkReturnTarget.value = null
            workspaceSessionCoordinator.reportError("돌아갈 원본 문서를 찾을 수 없습니다.")
            return
        }
        val key = runCatching { FileKey.of(path.relativePath) }.getOrNull()
        if (key == null) {
            _projectLinkReturnTarget.value = null
            workspaceSessionCoordinator.reportError("돌아갈 원본 문서를 찾을 수 없습니다.")
            return
        }
        selectFileInternal(key) {
            if (_projectLinkReturnTarget.value == path) _projectLinkReturnTarget.value = null
        }
    }

    private fun selectInternalLinkFile(sourceFile: ProjectFile, targetKey: FileKey) {
        if (targetKey == sourceFile.key) return
        val returnTarget = workspaceLinkPath(sourceFile)?.takeIf {
            it.workspaceKind == WorkspaceKind.PROJECT && targetKey.folder != FolderKey.Base
        }
        selectFileInternal(targetKey) {
            _projectLinkReturnTarget.value = returnTarget
        }
    }

    private fun openCrossWorkspaceDocument(
        resolved: WorkspaceLinkResolution.Resolved,
        generalReturnTarget: WorkspaceLinkPath? = null,
        clearGeneralReturnOnSuccess: Boolean = false,
    ) {
        val path = resolved.document.path
        val trace = WorkspaceLoadDiagnostics.begin(
            if (clearGeneralReturnOnSuccess) "cross-workspace-return" else "cross-workspace-open",
            "kind=${path.workspaceKind}",
        )
        workspaceSessionCoordinator.launchTransition {
            try {
                val vault = bookmarks.value.vaultData ?: error("Vault를 확인할 수 없습니다.")
                val workspace = (_projectList.value + _generalFolderList.value)
                    .firstOrNull { it.name == path.workspaceName }
                    ?: fileManager.listProject(vault).firstOrNull { it.name == path.workspaceName }
                    ?: error("연결된 작업 공간을 찾을 수 없습니다: ${path.workspaceName}")
                val targetFile = fileManager.workspaceLinkIndex.platformFile(path)
                    ?: error("연결된 문서를 찾을 수 없습니다: ${path.vaultRelativePath}")
                check(fileManager.workspaceExists(targetFile)) {
                    "연결된 문서를 찾을 수 없습니다: ${path.vaultRelativePath}"
                }
                fileManager.requestOpenWorkspace(workspace)
                if (fileManager.workspaceOpenRequest.value != null) {
                    fileManager.confirmWorkspaceOpen(path.workspaceKind)
                }
                check(fileManager.workspaceOpenRequest.value == null) { "연결된 작업 공간을 열지 못했습니다." }
                val key = FileKey.of(path.relativePath)
                fileManager.pickFile(ProjectFile(key, targetFile))
                _generalLinkReturnTarget.value = if (clearGeneralReturnOnSuccess) null else generalReturnTarget
                resolved.heading?.let { heading ->
                    _markdownNavigation.value = PendingMarkdownNavigation(
                        fileKey = key,
                        target = MarkdownNavigationTarget(++nextMarkdownNavigationId, headingPath = heading.path),
                    )
                } ?: resolved.block?.let { block ->
                    _markdownNavigation.value = PendingMarkdownNavigation(
                        fileKey = key,
                        target = MarkdownNavigationTarget(++nextMarkdownNavigationId, blockId = block.id),
                    )
                }
                trace.complete()
            } catch (error: Throwable) {
                trace.fail(error)
                throw error
            }
        }
    }

    fun isInternalDocumentLinkResolved(sourceFile: ProjectFile, target: String): Boolean {
        val index = currentWorkspaceLinkIndex() ?: return true
        val source = workspaceLinkPath(sourceFile) ?: return true
        val reference = parseWorkspaceLinkReference(source, target) ?: return false
        return index.resolve(source, reference) is WorkspaceLinkResolution.Resolved
    }

    suspend fun resolveInternalEmbed(sourceFile: ProjectFile, target: String): MarkdownEmbedPreview = runCatching {
        val source = workspaceLinkPath(sourceFile)
            ?: return@runCatching MarkdownEmbedPreview.Unavailable("현재 작업 공간을 확인할 수 없습니다.")
        val index = currentWorkspaceLinkIndex()
            ?: return@runCatching MarkdownEmbedPreview.Unavailable("링크 인덱스를 준비하는 중입니다.")
        val reference = WorkspaceLinkDocument.markdown(source, "![[$target]]").outgoing.singleOrNull()
            ?: return@runCatching MarkdownEmbedPreview.Unavailable("임베드 문법을 해석하지 못했습니다.")
        val resolved = index.resolve(source, reference) as? WorkspaceLinkResolution.Resolved
            ?: return@runCatching MarkdownEmbedPreview.Unavailable("임베드 대상을 찾을 수 없습니다.")
        resolved.document.issue?.let { issue ->
            return@runCatching MarkdownEmbedPreview.Unavailable(issue)
        }
        val file = fileManager.workspaceLinkIndex.platformFile(resolved.document.path)
            ?: return@runCatching MarkdownEmbedPreview.Unavailable("임베드 파일에 접근할 수 없습니다.")
        if (!fileManager.workspaceExists(file)) {
            return@runCatching MarkdownEmbedPreview.Unavailable("임베드 파일에 접근할 수 없습니다.")
        }
        when (resolved.document.resourceKind) {
            WorkspaceLinkResourceKind.MARKDOWN -> MarkdownEmbedPreview.Document(
                extractMarkdownEmbed(
                    raw = fileManager.workspaceLinkMarkdownReader(file),
                    heading = resolved.heading,
                    block = resolved.block,
                ),
            )
            WorkspaceLinkResourceKind.ATTACHMENT -> {
                if (resolved.document.path.fileName.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) {
                    MarkdownEmbedPreview.Attachment(resolved.document.path.fileName)
                } else {
                    val bytes = file.readBytes()
                    if (bytes.size > MAX_EMBED_IMAGE_BYTES) {
                        MarkdownEmbedPreview.Unavailable("이미지가 너무 커서 미리볼 수 없습니다.")
                    } else {
                        MarkdownEmbedPreview.Image(bytes.decodeToImageBitmap(), resolved.document.path.fileName)
                    }
                }
            }
        }
    }.getOrElse { error ->
        MarkdownEmbedPreview.Unavailable(error.message ?: "임베드를 불러오지 못했습니다.")
    }

    fun completeInternalDocumentLink(
        sourceFile: ProjectFile,
        request: MarkdownLinkCompletionRequest,
    ): List<MarkdownLinkCompletionCandidate> {
        val source = workspaceLinkPath(sourceFile) ?: return emptyList()
        val index = currentWorkspaceLinkIndex() ?: return emptyList()
        val candidates = when (request.kind) {
            MarkdownLinkCompletionKind.FILE -> index.completeFiles(source, request.query)
            MarkdownLinkCompletionKind.HEADING -> index.completeHeadings(source, request.file, request.query)
            MarkdownLinkCompletionKind.BLOCK -> {
                if (request.file == null && request.query.startsWith('^')) return emptyList()
                val target = (index.resolve(source, WorkspaceLinkTarget(request.file)) as? WorkspaceLinkResolution.Resolved)
                    ?.document
                val loaded = target?.takeIf {
                    it.path.workspaceKind == source.workspaceKind && it.path.workspaceName == source.workspaceName
                }?.let { loadedNote(FileKey.of(it.path.relativePath)) }
                index.completeBlocks(source, request.file, request.query,
                    documentOverride = loaded?.let { WorkspaceLinkDocument.markdown(checkNotNull(target).path, it.inject()) })
            }
        }
        return candidates.map { candidate ->
            val target = when {
                request.syntax == MarkdownLinkCompletionSyntax.WIKI &&
                    !request.hasExistingAlias && candidate.alias != null ->
                    "${candidate.target}|${candidate.alias}"
                request.syntax == MarkdownLinkCompletionSyntax.MARKDOWN -> candidate.target.replace(" ", "%20")
                else -> candidate.target
            }
            val replacement = request.displayText?.let { display ->
                "${target.substringBefore('|')}|$display"
            } ?: target
            MarkdownLinkCompletionCandidate(
                title = candidate.title,
                detail = candidate.detail,
                replacement = replacement,
                blockCreation = candidate.blockDraft?.let { draft ->
                    val current = candidate.path == source
                    val raw = if (current) loadedNote(sourceFile.key)?.inject() else null
                    val bodyOffset = raw?.let { it.length - NoteFile.parse(it).body.length } ?: 0
                    MarkdownBlockReferenceCreation(
                        path = candidate.path,
                        draft = if (current) draft.copy(
                            sourceRange = WorkspaceLinkSourceRange(
                                draft.sourceRange.start - bodyOffset, draft.sourceRange.endExclusive - bodyOffset),
                            insertionOffset = draft.insertionOffset - bodyOffset,
                        ) else draft,
                        isCurrentDocument = current,
                    )
                },
            )
        }
    }

    fun reportBlockReferenceError(message: String) = workspaceSessionCoordinator.reportError(message)

    /** Commit the target before completing a link. The source editor owns the final, guarded edit. */
    suspend fun createInternalBlockReference(
        sourceFile: ProjectFile,
        creation: MarkdownBlockReferenceCreation,
        isCurrent: () -> Boolean,
    ): String? {
        if (creation.isCurrentDocument || workspaceInputBlocked || !isCurrent() ||
            !blockReferenceCreations.add(creation.path)
        ) return null
        val context = currentWorkspaceReadContext()
        val sourceSession = editorSessionKeys[sourceFile.key]
        try {
            return fileReconciliationMutex.withLock {
                if (!isCurrentProjectFile(sourceFile, context) || !isCurrent()) return@withLock null
                saveCoordinator.withWritesPaused {
                    val file = fileManager.workspaceLinkIndex.platformFile(creation.path)
                        ?: error("블록 대상 파일을 다시 확인해 주세요.")
                    val activeTarget = creation.path.workspaceKind == context.workspaceKind &&
                        creation.path.workspaceName == bookmarks.value.projectData?.name
                    val key = FileKey.of(creation.path.relativePath)
                    val targetFile = if (activeTarget) knownProjectFiles[key] else null
                    val targetSession = if (activeTarget) editorSessionKeys[key] else null
                    val before = withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownReader(file) }
                    val disk = NoteFile.parse(before)
                    val pending = if (activeTarget) saveCoordinator.pendingValue(key) else null
                    if (pending != null && pending.expectedDisk.inject() != disk.inject()) {
                        targetFile?.let { acceptExternalDocument(key, it, disk, context) }
                        error("블록 대상이 외부에서 변경되었습니다. 후보를 다시 선택해 주세요.")
                    }
                    val loadedBefore = if (activeTarget) loadedNote(key) else null
                    val working = pending?.noteFile ?: loadedBefore?.takeIf { it.inject() == disk.inject() } ?: disk
                    val raw = if (working.inject() == disk.inject() && pending == null) before else working.inject()
                    val id = newWorkspaceBlockId(raw)
                    val direct = insertWorkspaceBlockId(raw, creation.draft, id)
                    val canonical = working.inject()
                    // Loaded-note candidates use the serializer's frontmatter separator; retain the disk's EOLs.
                    val draft = if (direct == null && raw != canonical && loadedBefore != null &&
                        insertWorkspaceBlockId(canonical, creation.draft, id) != null
                    ) {
                        val shift = raw.length - canonical.length
                        creation.draft.copy(
                            sourceRange = WorkspaceLinkSourceRange(creation.draft.sourceRange.start + shift,
                                creation.draft.sourceRange.endExclusive + shift),
                            insertionOffset = creation.draft.insertionOffset + shift,
                        )
                    } else creation.draft
                    val after = direct ?: insertWorkspaceBlockId(raw, draft, id)
                        ?: error("블록 내용이 변경되었습니다. 후보를 다시 선택해 주세요.")
                    val updated = NoteFile.parse(after)
                    fun stillCurrent(): Boolean = isCurrentWorkspace(context) && !workspaceInputBlocked && isCurrent() &&
                        isCurrentProjectFile(sourceFile, context) && editorSessionKeys[sourceFile.key] == sourceSession &&
                        (!activeTarget || editorSessionKeys[key] == targetSession &&
                            loadedNote(key)?.inject() == loadedBefore?.inject())
                    check(stillCurrent()) { "편집 내용이나 작업 공간이 변경되었습니다." }
                    check(withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownReader(file) } == before) {
                        "블록 대상이 외부에서 변경되었습니다."
                    }
                    withContext(NonCancellable) {
                        try {
                            withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownWriter(file, after) }
                            val modified = fileManager.lastModified(file)
                            if (activeTarget && targetFile != null) {
                                updateWorkspaceMetadataIndex(targetFile, updated, modified, context)
                            }
                            fileManager.workspaceLinkIndex.upsertByLocation(file.toString(), after, modified)
                            check(withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownReader(file) } == after) {
                                "블록 ID 저장 결과를 확인하지 못했습니다."
                            }
                            check(stillCurrent()) { "편집 내용이나 작업 공간이 변경되었습니다." }
                            if (activeTarget && targetFile != null) {
                                pageLoadJobs.remove(key)?.cancel()
                                saveCoordinator.cancel(key)
                                putLoadedNote(key, updated)
                                modified?.let { knownModified[key] = it }
                                updateGeneralSourceEntry(targetFile, updated, context)
                            }
                            id
                        } catch (failure: Exception) {
                            restoreBlockReferenceTarget(file, creation.path, before, after, failure, context)
                            throw failure
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isCurrentWorkspace(context)) reportBlockReferenceError(failure.message ?: "블록 ID를 저장하지 못했습니다.")
            return null
        } finally {
            blockReferenceCreations.remove(creation.path)
        }
    }

    private suspend fun restoreBlockReferenceTarget(
        file: PlatformFile,
        path: WorkspaceLinkPath,
        before: String,
        after: String,
        cause: Exception,
        context: WorkspaceReadContext,
    ) {
        try {
            val observed = withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownReader(file) }
            if (observed != before) {
                // A prefix can also be another writer's edit; only restore our exact receipt.
                check(observed == after) { "블록 대상이 외부에서 변경되어 원복하지 않았습니다." }
                withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownWriter(file, before) }
                check(withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownReader(file) } == before) {
                    "블록 ID 기록을 복구하지 못했습니다."
                }
            }
            val modified = fileManager.lastModified(file)
            fileManager.workspaceLinkIndex.upsertByLocation(file.toString(), before, modified)
            val key = FileKey.of(path.relativePath)
            val activeTarget = path.workspaceKind == context.workspaceKind &&
                path.workspaceName == bookmarks.value.projectData?.name
            val target = knownProjectFiles[key]?.takeIf { activeTarget && it.platformFile.toString() == file.toString() }
            target?.let { updateWorkspaceMetadataIndex(it, NoteFile.parse(before), modified, context) }
            check(withContext(Dispatchers.IO) { fileManager.workspaceLinkMarkdownReader(file) } == before) {
                "블록 대상이 복구 중 외부에서 변경되었습니다."
            }
            if (isCurrentWorkspace(context) && target != null && knownProjectFiles[key] == target) {
                modified?.let { knownModified[key] = it }
            }
        } catch (rollback: Exception) {
            cause.addSuppressed(rollback)
            if (isCurrentWorkspace(context)) quarantinePathChanges(setOf(path),
                IllegalStateException("블록 ID 기록의 복구 상태를 확인해 주세요. ${rollback.message.orEmpty()}", cause))
        }
    }

    private fun currentWorkspaceLinkIndex(): WorkspaceLinkIndex? = when (
        val state = fileManager.workspaceLinkIndexState.value
    ) {
        is WorkspaceLinkIndexState.Ready -> state.index
        is WorkspaceLinkIndexState.Building -> state.index
        is WorkspaceLinkIndexState.Error -> state.previousIndex
        WorkspaceLinkIndexState.Inactive -> null
    }

    private fun workspaceLinkPath(file: ProjectFile): WorkspaceLinkPath? {
        val bookmark = bookmarks.value
        val workspaceName = bookmark.projectData?.let(readWorkspaceName) ?: return null
        return runCatching {
            WorkspaceLinkPath(bookmark.workspaceKind, workspaceName, file.key.relativePath)
        }.getOrNull()
    }

    private fun parseWorkspaceLinkReference(source: WorkspaceLinkPath, target: String) =
        WorkspaceLinkDocument.markdown(source, "[[$target]]").outgoing.singleOrNull()

    /** Flush the captured file before presenting a destructive confirmation for that exact identity. */
    fun requestMoveFileToTrash(file: ProjectFile) {
        if (workspaceInputBlocked || fileTrashPreparationInProgress || _pendingFileTrash.value != null ||
            hasPendingPropertyDefinitionSync()
        ) return
        val context = currentWorkspaceReadContext()
        if (!isCurrentHierarchyFile(file, context)) return
        fileTrashPreparationInProgress = true
        viewModelScope.launch {
            try {
                fileReconciliationMutex.withLock {
                    if (!isCurrentHierarchyFile(file, context) || hasPendingPropertyDefinitionSync()) return@withLock
                    workspaceSaveCoordinator.runAfterFlush {
                        saveCoordinator.flushAll()
                        check(isCurrentHierarchyFile(file, context)) { "삭제할 파일이 변경되었거나 더 이상 존재하지 않습니다." }
                        _pendingFileTrash.value = FileTrashUiState(file = file)
                    }.onFailure { error ->
                        if (isCurrentWorkspace(context) && workspaceSaveCoordinator.lastErrorMessage.value == null) {
                            workspaceSessionCoordinator.reportError(error.message ?: "파일 삭제를 준비하지 못했습니다.")
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
        if (requested.busy || hasPendingPropertyDefinitionSync()) return
        val project = bookmarks.value.projectData ?: return
        val context = currentWorkspaceReadContext()
        _pendingFileTrash.value = requested.copy(busy = true, errorMessage = null)
        viewModelScope.launch {
            val entered = withMutationIndexGate(context, false) withLock@ {
                if (!isCurrentWorkspace(context)) return@withLock false
                workspaceSaveCoordinator.runAfterFlush {
                    saveCoordinator.flushAll()
                    check(!hasPendingPropertyDefinitionSync()) {
                        "기본 속성 설정을 저장한 뒤 파일을 삭제해 주세요."
                    }
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
                    if (messages.isNotEmpty()) workspaceSessionCoordinator.reportError(messages.joinToString("\n"))
                }.onFailure { error ->
                    if (isCurrentWorkspace(context) && _pendingFileTrash.value?.file?.sameIdentityAs(requested.file) == true) {
                        _pendingFileTrash.value = requested.copy(
                            busy = false,
                            errorMessage = error.message ?: "파일을 휴지통으로 이동하지 못했습니다.",
                        )
                    }
                }
                true
            }
            if (!entered && isCurrentWorkspace(context) &&
                _pendingFileTrash.value?.file?.sameIdentityAs(requested.file) == true) {
                _pendingFileTrash.value = requested.copy(busy = false,
                    errorMessage = workspaceTransitionError.value ?: "링크 색인 준비에 실패해 삭제하지 않았습니다.")
            }
        }
    }

    /** Focus return reconciles inventory; the short poll checks only the selected clean document. */
    internal suspend fun checkExternalChanges(refreshHierarchy: Boolean) {
        WorkspaceLoadDiagnostics.withLock(fileReconciliationMutex, "reconciliation-lock-wait") {
            if (workspaceInputBlocked) return@withLock
            val workspace = bookmarks.value
            val project = workspace.projectData ?: return@withLock
            val context = currentWorkspaceReadContext()
            if (!isCurrentWorkspace(context)) return@withLock
            if (!reconcileSelectedWorkspaceAvailability()) return@withLock

            // External inventory changes are reconciled on focus return, never on the short timer.
            if (refreshHierarchy) {
                refreshFoldersAndFiles(
                    project,
                    preferredKey = null,
                    cancelRemoved = true,
                    refreshGeneralSources = true,
                    refreshProjectPlotMetadata = true,
                )
                workspace.vaultData?.let { scheduleWorkspaceLinkIndexRefresh(it, "focus-return") }
            }
            if (!isCurrentWorkspace(context)) return@withLock

            _hierarchyState.value.currentFile?.let { refreshSelectedDocument(it, context) }
        }
    }

    private suspend fun refreshSelectedDocument(file: ProjectFile, context: WorkspaceReadContext) {
        val key = file.key
        val cached = loadedNote(key) ?: return
        val knownAtStart = knownModified[key]
        fun canPublish(): Boolean = isCurrentProjectFile(file, context) &&
            _hierarchyState.value.selectedFileKey == key &&
            loadedNote(key) === cached && knownModified[key] == knownAtStart &&
            !saveCoordinator.hasPending(key) && key !in _documentConflicts.value
        val metadata = bookmarks.value.projectData?.let {
            fileManager.workspaceMetadataIndex.snapshot(it, context.workspaceKind)?.entries?.get(key)
        }
        val unverifiedPositive = metadata?.modifiedAtVerified == false && metadata.modifiedAt?.let { it > 0L } == true
        if (!canPublish() || knownAtStart != null && knownAtStart <= 0L && !unverifiedPositive) return
        val diskModified = fileManager.lastModified(file.platformFile)?.takeIf { it > 0L } ?: return
        if (!canPublish() || knownAtStart == diskModified && !unverifiedPositive) return
        val read = fileManager.readMarkdownForDisplay(file.platformFile)
        val fresh = read.noteFile
        val refreshedModified = fileManager.lastModified(file.platformFile)?.takeIf { it > 0L } ?: return
        currentCoroutineContext().ensureActive()
        if (!canPublish() || refreshedModified != diskModified) return
        if (fresh.inject() != cached.inject()) {
            acceptExternalDocument(key, file, fresh, context, refreshedModified, expectedMetadataIdentity = read.metadataIdentity)
        } else {
            knownModified[key] = refreshedModified
            updateWorkspaceMetadataIndex(file, fresh, refreshedModified, context, read.metadataIdentity)
            updateGeneralSourceEntry(file, fresh, context)
        }
        val acceptedSnapshot = if (fresh.inject() != cached.inject()) fresh else cached
        if (isCurrentProjectFile(file, context) && loadedNote(key) === acceptedSnapshot && !saveCoordinator.hasPending(key)) {
            fileManager.acceptWorkspaceDocumentRead(file.platformFile, read)
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
            loadPage(file)
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
                    workspaceSessionCoordinator.reportError(
                        failure.message ?: "문서 위치를 확인하지 못했습니다. 초안을 유지했습니다.",
                    )
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

    private suspend fun acceptExternalDocument(
        fileKey: FileKey,
        file: ProjectFile,
        fresh: NoteFile,
        context: WorkspaceReadContext,
        observedModifiedAt: Long? = null,
        cancelPending: Boolean = true,
        expectedMetadataIdentity: com.ninetag.machum.external.WorkspaceMetadataIdentity? = null,
    ) {
        if (!isCurrentWorkspace(context)) return
        if (cancelPending) saveCoordinator.cancel(fileKey)
        if (pageLoadJobs[fileKey] !== currentCoroutineContext().job) {
            pageLoadJobs.remove(fileKey)?.cancel()
        }
        editorSessionKeys.remove(fileKey)
        putLoadedNote(fileKey, fresh)
        val modifiedAt = observedModifiedAt ?: fileManager.lastModified(file.platformFile)
        knownModified[fileKey] = modifiedAt ?: 0L
        updateWorkspaceMetadataIndex(file, fresh, modifiedAt, context, expectedMetadataIdentity)
        updateGeneralSourceEntry(file, fresh, context)
    }

    private fun loadPage(projectFile: ProjectFile, force: Boolean) {
        if (workspaceInputBlocked) return
        val key = projectFile.key
        val file = projectFile.platformFile
        val context = currentWorkspaceReadContext()
        if (!isCurrentWorkspace(context)) return
        knownProjectFiles[key] = projectFile
        val currentState = _fileLoadStates.value[key]
        val requestTrace = WorkspaceLoadDiagnostics.begin("document-open-request", "reason=document-open|generation=${context.generation}")
        if (!force) {
            if (currentState is FileLoadUiState.Loading) {
                requestTrace.complete("mode=skipped|reason=already-loading")
                return
            }
            if (currentState is FileLoadUiState.Loaded &&
                (_hierarchyState.value.selectedFileKey != key || saveCoordinator.hasPending(key) ||
                    key in _documentConflicts.value)) {
                requestTrace.complete("mode=skipped|reason=already-loaded")
                return
            }
        }
        pageLoadJobs.remove(key)?.cancel()
        // Both workspace kinds render their cached editor while checking its modification time.
        if (currentState !is FileLoadUiState.Loaded) {
            setFileLoadState(key, FileLoadUiState.Loading)
        }

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val trace = WorkspaceLoadDiagnostics.begin(
                "document-load",
                "generation=${context.generation}|reason=document-open|triggerOp=${requestTrace.operation?.id}",
            )
            try {
                if (!force && currentState is FileLoadUiState.Loaded) {
                    fileReconciliationMutex.withLock { refreshSelectedDocument(projectFile, context) }
                    trace.complete("mode=mtime-check")
                    return@launch
                }
                if (!force) {
                    val workspace = bookmarks.value.projectData
                    val remembered = workspace?.let {
                        fileManager.workspaceMetadataIndex.snapshot(it, context.workspaceKind)?.entries?.get(key)
                    }?.takeIf { it.file.platformFile.toString() == file.toString() && it.noteFile != null }
                    if (remembered != null) {
                        if (!isCurrentProjectFile(projectFile, context) ||
                            pageLoadJobs[key] !== currentCoroutineContext().job) return@launch
                        putLoadedNote(key, checkNotNull(remembered.noteFile))
                        knownModified[key] = remembered.modifiedAt ?: 0L
                        fileReconciliationMutex.withLock { refreshSelectedDocument(projectFile, context) }
                        trace.complete("mode=process-cache|published=true")
                        return@launch
                    }
                }
                val read = WorkspaceLoadDiagnostics.within(trace, "document-open") { fileManager.readMarkdownForDisplay(file) }
                val markdown = read.noteFile
                val modifiedAt = WorkspaceLoadDiagnostics.within(trace, "document-open") { fileManager.lastModified(file) }
                currentCoroutineContext().ensureActive()
                if (!isCurrentProjectFile(projectFile, context) ||
                    pageLoadJobs[key] !== currentCoroutineContext().job ||
                    (currentState is FileLoadUiState.Loaded && loadedNote(key) !== currentState.noteFile) ||
                    saveCoordinator.hasPending(key)) {
                    trace.complete("published=false|reason=file-changed")
                    return@launch
                }
                putLoadedNote(key, markdown)
                knownModified[key] = modifiedAt ?: 0L
                updateWorkspaceMetadataIndex(projectFile, markdown, modifiedAt, context, read.metadataIdentity)
                updateGeneralSourceEntry(projectFile, markdown, context)
                if (isCurrentProjectFile(projectFile, context) && loadedNote(key) === markdown && !saveCoordinator.hasPending(key)) {
                    fileManager.acceptWorkspaceDocumentRead(file, read)
                }
                trace.complete("published=true")
            } catch (cancellation: CancellationException) {
                trace.fail(cancellation)
                throw cancellation
            } catch (error: Exception) {
                trace.fail(error)
                if (!isCurrentProjectFile(projectFile, context) ||
                    pageLoadJobs[key] !== currentCoroutineContext().job || saveCoordinator.hasPending(key)) return@launch
                if (loadedNote(key) != null) {
                    workspaceSessionCoordinator.reportError(error.message ?: "문서를 새로 확인하지 못했습니다.")
                    return@launch
                }
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
        requestTrace.complete("mode=queued")
    }

    fun updateBody(fileKey: FileKey, newBody: String) {
        if (commitHistoryUiState.value.restore?.isRestoring == true) return
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
        activeFileMoveInputs[fileKey]?.let { moveInput ->
            // The provider path is between identities. Keep typing in memory until the move
            // publishes the new key so no callback can recreate the old path.
            moveInput.noteFile = updated
            return
        }
        val projectFile = knownProjectFiles[fileKey] ?: return
        val expectedDisk = saveCoordinator.pendingValue(fileKey)?.expectedDisk ?: current
        saveCoordinator.schedule(
            fileKey,
            PendingWrite(projectFile, updated, expectedDisk, currentWorkspaceReadContext()),
        )
    }

    internal suspend fun saveDocumentProperties(
        fileKey: FileKey,
        change: PreparedPropertyChange,
        expectedEditorSessionKey: String? = null,
    ): String? =
        saveDocumentProperties(
            fileKey = fileKey,
            expectedNote = change.expected,
            updatedNote = change.updated,
            definitionChange = change.definitionChange,
            expectedEditorSessionKey = expectedEditorSessionKey,
        )

    /** Compatibility entry point for callers that only update frontmatter and have no default-definition intent. */
    suspend fun saveDocumentProperties(fileKey: FileKey, expectedNote: NoteFile, updatedNote: NoteFile): String? =
        saveDocumentProperties(
            fileKey,
            expectedNote,
            updatedNote,
            definitionChange = null,
            expectedEditorSessionKey = null,
        )

    /** 디스크 기준을 한 번 확인한 뒤 최신 editor body와 속성을 같은 write fence 안에서 병합한다. */
    private suspend fun saveDocumentProperties(
        fileKey: FileKey,
        expectedNote: NoteFile,
        updatedNote: NoteFile,
        definitionChange: DocumentPropertyDefinitionChange?,
        expectedEditorSessionKey: String?,
    ): String? {
        val context = currentWorkspaceReadContext()
        if (workspaceInputBlocked) return "작업 공간을 다시 열어 주세요."
        return fileReconciliationMutex.withLock {
            if (workspaceInputBlocked || !isCurrentWorkspace(context)) return@withLock "작업 공간이 변경되었습니다."
            _documentConflicts.value[fileKey]?.let { return@withLock it }
            if (expectedEditorSessionKey != null && editorSessionKeys[fileKey] != expectedEditorSessionKey) {
                return@withLock "외부에서 변경된 최신 문서를 적용했습니다."
            }
            val file = knownProjectFiles[fileKey] ?: return@withLock "문서를 다시 열어 주세요."
            var committedNote: NoteFile? = null
            var committedModifiedAt: Long? = null
            val documentError = try {
                check(isCurrentProjectFile(file, context)) { "문서 위치가 변경되었습니다." }
                saveCoordinator.withWritesPaused {
                    if (expectedEditorSessionKey != null && editorSessionKeys[fileKey] != expectedEditorSessionKey) {
                        error("외부에서 변경된 최신 문서를 적용했습니다.")
                    }
                    val disk = fileManager.readMarkdown(file.platformFile)
                    check(isCurrentProjectFile(file, context)) { "작업 공간이 변경되었습니다." }
                    val latest = loadedNote(fileKey)
                    if (disk.withBody("", false).inject() != expectedNote.withBody("", false).inject()) {
                        if (latest?.inject() != disk.inject()) {
                            acceptExternalDocument(fileKey, file, disk, context)
                            error("외부에서 변경된 최신 문서를 적용했습니다.")
                        }
                        error("문서 정보가 먼저 변경되었습니다. 최신 값에서 다시 적용해 주세요.")
                    }
                    val managed = if (context.workspaceKind == WorkspaceKind.PROJECT) {
                        com.ninetag.machum.entity.normalizeTags(
                            listOfNotNull(bookmarks.value.projectData?.name) + projectConfig.value?.effectiveAutoTags(fileKey.folder.relativePath).orEmpty())
                    } else emptyList()
                    val protection = DocumentPropertyProtectionPolicy(
                        managedTagNames = managed.toSet(),
                        sourceIsManaged = context.workspaceKind == WorkspaceKind.GENERAL &&
                            _generalSourceState.value?.enabled != false,
                    )
                    protection.persistedChangeError(disk, updatedNote)?.let { error(it) }
                    val body = when {
                        disk.body == expectedNote.body -> latest?.body ?: disk.body
                        latest?.inject() == disk.inject() -> disk.body
                        else -> {
                            acceptExternalDocument(fileKey, file, disk, context)
                            error("외부에서 변경된 최신 문서를 적용했습니다.")
                        }
                    }
                    val merged = updatedNote.withBody(body, false)
                    withContext(NonCancellable) {
                        if (merged.inject() != disk.inject()) {
                            pageLoadJobs.remove(fileKey)?.cancel()
                            writeNote(file.platformFile, merged)
                        }
                        committedModifiedAt = fileManager.lastModified(file.platformFile)
                        knownModified[fileKey] = committedModifiedAt ?: 0L
                        // provider I/O 중 들어온 입력은 커밋된 속성 위로 올리고 다음 auto-save에 맡긴다.
                        val after = loadedNote(fileKey)
                        val rebased = merged.withBody(
                            if (after != null && after.body != latest?.body) after.body else merged.body,
                            false,
                        )
                        saveCoordinator.cancel(fileKey)
                        putLoadedNote(fileKey, rebased)
                        if (rebased.body != merged.body) {
                            saveCoordinator.schedule(
                                fileKey,
                                PendingWrite(file, rebased, merged, context),
                            )
                        }
                        committedNote = rebased
                    }
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.message ?: "문서를 저장하지 못했습니다."
            }
            if (documentError == null && isCurrentWorkspace(context)) {
                val authoritative = loadedNote(fileKey) ?: committedNote
                if (authoritative != null) {
                    updateWorkspaceMetadataIndex(file, authoritative, committedModifiedAt, context)
                    updateGeneralSourceEntry(file, authoritative, context)
                }
            }
            if (documentError == null && definitionChange != null) {
                withContext(NonCancellable) {
                    syncPropertyDefinitions(fileKey, context, definitionChange)
                }
            }
            documentError
        }
    }

    fun retryPropertyDefinitionSync(fileKey: FileKey) {
        val pending = pendingPropertyDefinitionSyncs[fileKey] ?: return
        viewModelScope.launch {
            fileReconciliationMutex.withLock {
                // Definition persistence depends on the captured workspace and scope, not on the
                // Markdown file still being present. External deletion must not strand a pending
                // definition and permanently block commit or workspace navigation.
                if (!isCurrentWorkspace(pending.context)) return@withLock
                withContext(NonCancellable) { syncPropertyDefinitions(fileKey, pending.context, null) }
            }
        }
    }

    private suspend fun syncPropertyDefinitions(
        fileKey: FileKey,
        context: WorkspaceReadContext,
        newChange: DocumentPropertyDefinitionChange?,
    ) {
        val previous = pendingPropertyDefinitionSyncs[fileKey]
            ?.takeIf { it.context.matches(context) }
            ?.changes
            .orEmpty()
        val scopePath = when (context.workspaceKind) {
            WorkspaceKind.PROJECT -> fileKey.folder.relativePath
            WorkspaceKind.GENERAL -> GENERAL_PROPERTY_DEFINITION_SCOPE
        }
        val changes = previous + newChange.toVersionedDefinitionComponents(scopePath)
        if (changes.isEmpty()) return
        pendingPropertyDefinitionSyncs[fileKey] = PendingPropertyDefinitionSync(context, changes)
        _propertyDefinitionSyncUiState.value = _propertyDefinitionSyncUiState.value +
            (fileKey to PropertyDefinitionSyncUiState(isRetrying = true))
        val error = try {
            check(isCurrentWorkspace(context)) { "작업 공간이 변경되어 기본 속성 설정을 저장하지 않았습니다." }
            val currentChanges = changes
                .filter(::isLatestDefinitionComponent)
                .map(VersionedPropertyDefinitionChange::change)
            if (currentChanges.isEmpty()) {
                pendingPropertyDefinitionSyncs.remove(fileKey)
                _propertyDefinitionSyncUiState.value = _propertyDefinitionSyncUiState.value - fileKey
                return
            }
            when (context.workspaceKind) {
                WorkspaceKind.PROJECT -> {
                    if (newChange == null) {
                        checkNotNull(fileManager.reloadCurrentProjectConfig()) {
                            "프로젝트 속성 설정을 다시 불러오지 못했습니다."
                        }
                        check(isCurrentWorkspace(context)) { "작업 공간이 변경되었습니다." }
                    }
                    checkNotNull(fileManager.updateDocumentPropertyDefinitions(fileKey.folder.relativePath, currentChanges)) {
                        "프로젝트 속성 설정을 저장하지 못했습니다."
                    }
                }
                WorkspaceKind.GENERAL -> {
                    val workspace = bookmarks.value.projectData
                        ?: error("General 작업 공간을 다시 열어 주세요.")
                    val state = fileManager.generalSources.updateDocumentPropertyDefinitions(workspace, currentChanges)
                    check(isCurrentWorkspace(context)) { "작업 공간이 변경되었습니다." }
                    _generalSourceState.value = state
                }
            }
            check(isCurrentWorkspace(context)) { "작업 공간이 변경되었습니다." }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.message ?: "이후 새 문서에 적용할 기본 속성 설정을 저장하지 못했습니다."
        }
        if (error == null) {
            pendingPropertyDefinitionSyncs.remove(fileKey)
            _propertyDefinitionSyncUiState.value = _propertyDefinitionSyncUiState.value - fileKey
        } else {
            _propertyDefinitionSyncUiState.value = _propertyDefinitionSyncUiState.value +
                (fileKey to PropertyDefinitionSyncUiState(error, isRetrying = false))
        }
    }

    private fun DocumentPropertyDefinitionChange?.toVersionedDefinitionComponents(
        scopePath: String,
    ): List<VersionedPropertyDefinitionChange> {
        val normalized = this?.normalized() ?: return emptyList()
        val previousKey = normalized.previousKey
        val key = normalized.key
        val revision = ++propertyDefinitionRevision
        val components = buildList {
            if (previousKey != null && previousKey != key) {
                add(
                    VersionedPropertyDefinitionChange(
                        change = DocumentPropertyDefinitionChange(previousKey, null, null),
                        revision = revision,
                        membership = scopePath to previousKey,
                    ),
                )
            }
            if (key != null && previousKey != key) {
                add(
                    VersionedPropertyDefinitionChange(
                        change = DocumentPropertyDefinitionChange(null, key, null),
                        revision = revision,
                        membership = scopePath to key,
                    ),
                )
            }
            if (key != null && normalized.type != null) {
                add(
                    VersionedPropertyDefinitionChange(
                        change = DocumentPropertyDefinitionChange(key, key, normalized.type),
                        revision = revision,
                        typeKey = key,
                    ),
                )
            }
        }
        components.forEach { component ->
            component.membership?.let { latestPropertyMembershipRevision[it] = revision }
            component.typeKey?.let { latestPropertyTypeRevision[it] = revision }
        }
        return components
    }

    private fun isLatestDefinitionComponent(component: VersionedPropertyDefinitionChange): Boolean {
        val latestMembership = component.membership?.let(latestPropertyMembershipRevision::get)
        val latestType = component.typeKey?.let(latestPropertyTypeRevision::get)
        return (latestMembership == null || latestMembership == component.revision) &&
            (latestType == null || latestType == component.revision)
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
                if (pending != null) {
                    saveCoordinator.schedule(
                        file.key,
                        PendingWrite(file, merged, fresh, currentWorkspaceReadContext()),
                    )
                }
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
        if (hasPendingPropertyDefinitionSync()) {
            if (reportErrors) workspaceSessionCoordinator.reportError(
                "기본 속성 설정을 저장한 뒤 디렉터리 설정을 변경해 주세요.",
            )
            return false
        }
        var updated = false
        val requestedContext = currentWorkspaceReadContext()
        val previousCommit = committedFolderSettings?.takeIf {
            it.context == requestedContext && it.originalKey == folderKey &&
                it.name == updatedName && (transform?.invoke(it.config) ?: folderConfig) == it.config
        }
        if (previousCommit == null) committedFolderSettings = null
        var conflictMessage: String? = null
        runWorkspaceMutation(context = requestedContext, reportErrors = reportErrors) { project, context ->
            if (hasPendingPropertyDefinitionSync()) {
                if (reportErrors) workspaceSessionCoordinator.reportError(
                    "기본 속성 설정을 저장한 뒤 디렉터리 설정을 변경해 주세요.",
                )
                return@runWorkspaceMutation
            }
            // runAfterFlush has completed the pending writes from the committed rename/config.
            // Do not re-read/re-tag an item at the new path on a retry of that same request.
            if (previousCommit != null && committedFolderSettings === previousCommit) {
                committedFolderSettings = null
                workspaceSessionCoordinator.clearError()
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
                plan.files.keys.asSequence()
                    .mapNotNull { key -> _documentConflicts.value[key] }
                    .firstOrNull()
                    ?.let { conflict ->
                        conflictMessage = conflict
                        return@withWritesPaused null
                    }
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
                if (reportErrors) workspaceSessionCoordinator.reportError(
                    conflictMessage ?: "디렉터리 설정을 변경하지 못했습니다. 이름과 폴더 접근 권한을 확인해 주세요.",
                )
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
                if (reportErrors) workspaceSessionCoordinator.reportError(
                    "디렉터리 설정은 변경했지만 일부 문서 저장에 실패했습니다. " +
                        "내용은 메모리에 보관 중이며 다음 편집이나 작업 전환 시 다시 저장합니다. ${error.message.orEmpty()}",
                )
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
                saveCoordinator.schedule(
                    key,
                    PendingWrite(
                        projectFile,
                        updated,
                        pending?.expectedDisk ?: snapshot?.noteFile ?: original ?: updated,
                        currentWorkspaceReadContext(),
                    ),
                )
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
        if (hasPendingPropertyDefinitionSync()) {
            return "기본 속성 설정을 저장한 뒤 파일 이름을 변경해 주세요."
        }
        val context = currentWorkspaceReadContext()
        val trace = WorkspaceLoadDiagnostics.begin("file-rename", "kind=${context.workspaceKind}")
        return WorkspaceLoadDiagnostics.within(trace, "rename") {
        val lockTrace = WorkspaceLoadDiagnostics.start("file-rename-lock")
        try {
            val result = withMutationIndexGate<String?>(context, "링크 인덱스를 준비하지 못해 이름 변경을 중단했습니다.") withLock@ {
                lockTrace.complete()
                _documentConflicts.value[projectFile.key]?.let { return@withLock it }
                if (hasPendingPropertyDefinitionSync()) {
                    return@withLock "기본 속성 설정을 저장한 뒤 파일 이름을 변경해 주세요."
                }
                if (!isCurrentProjectFile(projectFile, context)) return@withLock "파일 선택이 변경되었습니다."
                // The link index must see unsaved references before planning a path change.
                run {
                    _documentConflicts.value.values.firstOrNull()?.let { return@withLock it }
                    val flushTrace = WorkspaceLoadDiagnostics.begin("file-rename-flush")
                    try {
                        saveCoordinator.flushAll()
                        flushTrace.complete()
                    } catch (cancellation: CancellationException) {
                        flushTrace.fail(cancellation)
                        throw cancellation
                    } catch (error: Exception) {
                        flushTrace.fail(error)
                        return@withLock "문서를 저장하지 못해 이름 변경을 중단했습니다. ${error.message.orEmpty()}"
                    }
                }
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
            trace.complete("success=${result == null}")
            result
        } catch (error: Throwable) {
            lockTrace.fail(error)
            trace.fail(error)
            throw error
        }
        }
    }

    /**
     * Moves an existing document inside the currently open Project workspace.
     *
     * The source does not have to be the document shown by the editor. The hierarchy snapshot is
     * authoritative for unopened files, while loaded editor state is migrated only when it exists.
     */
    suspend fun moveFile(
        projectFile: ProjectFile,
        targetFolderKey: FolderKey,
        targetPlotStage: PlotStage? = null,
    ): String? = moveFileResult(projectFile, targetFolderKey, targetPlotStage).errorMessage

    suspend fun moveFileResult(
        projectFile: ProjectFile,
        targetFolderKey: FolderKey,
        targetPlotStage: PlotStage? = null,
    ): FileMoveResult {
        var finalKey = projectFile.key
        val errorMessage = moveFileError(projectFile, targetFolderKey, targetPlotStage) { movedKey ->
            finalKey = movedKey
        }
        return FileMoveResult(finalKey, errorMessage)
    }

    private suspend fun moveFileError(
        projectFile: ProjectFile,
        targetFolderKey: FolderKey,
        targetPlotStage: PlotStage?,
        onFinalKey: (FileKey) -> Unit,
    ): String? {
        _documentConflicts.value[projectFile.key]?.let { return it }
        if (workspaceInputBlocked) return "작업 공간 선택 중에는 파일을 이동할 수 없습니다."
        if (hasPendingPropertyDefinitionSync()) {
            return "기본 속성 설정을 저장한 뒤 파일을 이동해 주세요."
        }
        val context = currentWorkspaceReadContext()
        if (context.workspaceKind != WorkspaceKind.PROJECT) {
            return "Project 작업 공간의 파일만 이동할 수 있습니다."
        }
        return withMutationIndexGate<String?>(context, "링크 인덱스를 준비하지 못해 파일 이동을 중단했습니다.") withLock@ {
            if (!isCurrentWorkspace(context) || context.workspaceKind != WorkspaceKind.PROJECT) {
                return@withLock "작업 공간이 변경되었습니다."
            }
            _documentConflicts.value[projectFile.key]?.let { return@withLock it }
            if (hasPendingPropertyDefinitionSync()) {
                return@withLock "기본 속성 설정을 저장한 뒤 파일을 이동해 주세요."
            }

            val hierarchy = _hierarchyState.value
            val sourceContent = hierarchy.folderContents[projectFile.key.folder]
                ?: return@withLock "현재 파일의 디렉터리를 찾을 수 없습니다."
            val source = sourceContent.files
                .find { candidate -> candidate.sameIdentityAs(projectFile) }
                ?: return@withLock "파일 위치가 변경되었습니다. 목록을 새로 확인해 주세요."
            val sourcePlotStage = sourceContent.plotEntries
                .firstOrNull { entry -> entry.projectFile.sameIdentityAs(source) }
                ?.stage
            val sourceFolder = hierarchy.folderList.find { it.key == source.key.folder }
                ?: return@withLock "현재 파일의 디렉터리를 찾을 수 없습니다."
            val targetFolder = hierarchy.folderList.find { it.key == targetFolderKey }
                ?: return@withLock "이동할 디렉터리를 찾을 수 없습니다."
            val sourceConfig = folderConfig(source.key.folder)
            val targetConfig = folderConfig(targetFolderKey)
            val targetContent = hierarchy.folderContents[targetFolderKey] ?: HierarchyFolderContent()
            if (targetConfig.isPlot != (targetPlotStage != null)) {
                return@withLock if (targetConfig.isPlot) {
                    "Plot 디렉터리의 구분을 선택해 주세요."
                } else {
                    "일반 디렉터리에는 Plot 구분을 지정할 수 없습니다."
                }
            }
            if (source.key.folder == targetFolderKey && sourcePlotStage == targetPlotStage) {
                return@withLock null
            }
            val movePlan = projectFileMoveNamePlan(
                source = source,
                sourceContent = sourceContent,
                sourceConfig = sourceConfig,
                sourcePlotStage = sourcePlotStage,
                targetFolder = targetFolderKey,
                targetContent = targetContent,
                targetConfig = targetConfig,
                targetPlotStage = targetPlotStage,
            )
            val expectedMarkdownKeys = (sourceContent.files + targetContent.files)
                .mapTo(mutableSetOf(), ProjectFile::key)
            val affectedKeys = movePlan.mapTo(mutableSetOf()) { it.projectFile.key }
            affectedKeys.asSequence()
                .mapNotNull { key -> _documentConflicts.value[key] }
                .firstOrNull()
                ?.let { conflict -> return@withLock conflict }
            val movedTargetName = movePlan.single { it.projectFile.key == source.key }.let { "${it.finalBaseName}.md" }
            if (hierarchy.folderContents[targetFolderKey]?.files.orEmpty().any { candidate ->
                    candidate.key !in affectedKeys && candidate.key.fileName.equals(movedTargetName, ignoreCase = true)
                }
            ) {
                return@withLock "이동할 디렉터리에 같은 이름의 파일이 있습니다."
            }
            // Save the source and its peers before any path mutation. Input arriving afterward
            // is still buffered/rebased by the existing move transaction.
            try {
                saveCoordinator.flushAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return@withLock "문서를 저장하지 못해 파일 이동을 중단했습니다. ${error.message.orEmpty()}"
            }

            saveCoordinator.withWritesPaused {
                currentCoroutineContext().ensureActive()
                if (!isCurrentWorkspace(context)) return@withWritesPaused "작업 공간이 변경되었습니다."
                withContext(NonCancellable) {
                    moveFileWithWritesPaused(
                        source,
                        sourceFolder,
                        targetFolder,
                        targetPlotStage,
                        movePlan,
                        expectedMarkdownKeys,
                        context,
                        onFinalKey,
                    )
                }
            }
        }
    }

    private suspend fun moveFileWithWritesPaused(
        source: ProjectFile,
        sourceFolder: ProjectFolder,
        targetFolder: ProjectFolder,
        targetPlotStage: PlotStage?,
        movePlan: List<ProjectFileMoveNamePlan>,
        expectedMarkdownKeys: Set<FileKey>,
        context: WorkspaceReadContext,
        onFinalKey: (FileKey) -> Unit,
    ): String? {
        val oldKey = source.key
        val inputBuffer = FileMoveInputBuffer()
        check(activeFileMoveInputs.put(oldKey, inputBuffer) == null) { "파일 이동이 이미 진행 중입니다." }
        return try {
            moveFileWithInputBuffered(
                source,
                sourceFolder,
                targetFolder,
                targetPlotStage,
                movePlan,
                expectedMarkdownKeys,
                context,
                inputBuffer,
                onFinalKey,
            )
        } finally {
            if (activeFileMoveInputs[oldKey] === inputBuffer) activeFileMoveInputs.remove(oldKey)
        }
    }

    private suspend fun moveFileWithInputBuffered(
        source: ProjectFile,
        sourceFolder: ProjectFolder,
        targetFolder: ProjectFolder,
        targetPlotStage: PlotStage?,
        movePlan: List<ProjectFileMoveNamePlan>,
        expectedMarkdownKeys: Set<FileKey>,
        context: WorkspaceReadContext,
        inputBuffer: FileMoveInputBuffer,
        onFinalKey: (FileKey) -> Unit,
    ): String? {
        val oldKey = source.key
        val loadedAtMoveStart = loadedNote(oldKey)
        val initialPending = saveCoordinator.cancel(oldKey)
        val movePlanByOldKey = movePlan.associateBy { it.projectFile.key }
        val peerKeys = movePlan
            .asSequence()
            .map(ProjectFileMoveNamePlan::projectFile)
            .map(ProjectFile::key)
            .filter { it != oldKey }
            .toSet()
        val interruptedPeerLoads = movePlan
            .asSequence()
            .map(ProjectFileMoveNamePlan::projectFile)
            .map(ProjectFile::key)
            .filter { key -> key != oldKey && _fileLoadStates.value[key] is FileLoadUiState.Loading }
            .toSet()
        movePlan.asSequence()
            .map(ProjectFileMoveNamePlan::projectFile)
            .filter { it.key != oldKey }
            .forEach { file -> pageLoadJobs.remove(file.key)?.cancel() }
        val interruptedPageLoad = _fileLoadStates.value[oldKey] is FileLoadUiState.Loading
        pageLoadJobs.remove(oldKey)?.cancel()
        val physicalMoveRequired = sourceFolder.key != targetFolder.key
        fun failedMove(message: String): String {
            if (interruptedPageLoad && _fileLoadStates.value[oldKey] is FileLoadUiState.Loading) {
                setFileLoadState(
                    oldKey,
                    FileLoadUiState.Error("파일 이동을 완료하지 못했습니다. 문서를 다시 열어 주세요."),
                )
            }
            if (interruptedPeerLoads.isNotEmpty()) {
                _fileLoadStates.value = _fileLoadStates.value.toMutableMap().apply {
                    interruptedPeerLoads.forEach { key ->
                        if (this[key] is FileLoadUiState.Loading) {
                            this[key] = FileLoadUiState.Error(
                                "파일 이동을 완료하지 못했습니다. 문서를 다시 열어 주세요.",
                            )
                        }
                    }
                }
            }
            return message
        }

        fun drainPeerPending(): Map<FileKey, PendingWrite> = peerKeys
            .mapNotNull { key -> saveCoordinator.cancel(key)?.let { key to it } }
            .toMap()

        suspend fun restorePeerPending(restoredFiles: Map<FileKey, ProjectFile> = emptyMap()) {
            val files = peerKeys.associateWith { key ->
                restoredFiles[key] ?: try {
                    fileManager.findProjectFile(key)
                } catch (_: Exception) {
                    null
                } ?: movePlanByOldKey.getValue(key).projectFile
            }
            drainPeerPending().forEach { (key, pending) ->
                val file = files.getValue(key)
                replaceProjectFileHandle(file)
                saveCoordinator.schedule(key, pending.copy(projectFile = file))
            }
        }

        val diskBaselineBeforeRead = initialPending?.expectedDisk ?: loadedAtMoveStart
        val sourceDiskBeforeMove = runCatching { fileManager.readMarkdown(source.platformFile) }.getOrElse { readError ->
            restorePeerPending()
            val pending = inputBuffer.noteFile?.let { latest ->
                PendingWrite(source, latest, diskBaselineBeforeRead ?: latest, context)
            } ?: initialPending
            pending?.let { saveCoordinator.schedule(oldKey, it) }
            return failedMove(readError.message ?: "이동할 파일을 확인하지 못했습니다.")
        }
        val localDiskBaseline = initialPending?.expectedDisk ?: loadedAtMoveStart ?: sourceDiskBeforeMove
        val externalWonAtMoveStart = sourceDiskBeforeMove.inject() != localDiskBaseline.inject()

        val moveBatch = try {
            fileManager.moveProjectFileAndRenumber(
                projectFile = source,
                targetFolder = targetFolder,
                assignments = movePlan.map { plan ->
                    ProjectFileMoveAssignment(plan.projectFile, plan.finalFolder, plan.finalBaseName)
                },
                expectedMarkdownKeys = expectedMarkdownKeys,
            )
        } catch (error: ProjectFileMoveRollbackException) {
            return failedMove(
                quarantineIncompleteMove(
                    source,
                    context,
                    initialPending,
                    inputBuffer,
                    error,
                ),
            )
        } catch (error: Exception) {
            restorePeerPending()
            val authoritativeSource = try {
                fileManager.findProjectFile(oldKey)
            } catch (_: Exception) {
                null
            } ?: source
            replaceProjectFileHandle(authoritativeSource)
            pendingForFailedMove(authoritativeSource, context, initialPending, inputBuffer, localDiskBaseline)
                ?.let { pending -> saveCoordinator.schedule(oldKey, pending) }
            return failedMove(error.message ?: "파일을 이동하지 못했습니다.")
        } ?: run {
            restorePeerPending()
            val authoritativeSource = try {
                fileManager.findProjectFile(oldKey)
            } catch (_: Exception) {
                null
            } ?: source
            replaceProjectFileHandle(authoritativeSource)
            pendingForFailedMove(authoritativeSource, context, initialPending, inputBuffer, localDiskBaseline)
                ?.let { pending -> saveCoordinator.schedule(oldKey, pending) }
            return failedMove("파일을 이동하지 못했습니다. 대상 위치와 접근 권한을 확인해 주세요.")
        }
        val moved = moveBatch.movedFile

        // Read the provider-owned bytes from the new location after the native move. Reading an
        // unopened file before the move would let an external edit in the read-to-move gap be
        // overwritten by the metadata write below.
        val movedDiskNote = runCatching { fileManager.readMarkdown(moved.platformFile) }.getOrElse { readError ->
            val restoredBatch = try {
                fileManager.moveProjectFileAndRenumber(
                    projectFile = moved,
                    targetFolder = sourceFolder,
                    assignments = moveBatch.filesByPreviousKey.map { (previousKey, currentFile) ->
                        ProjectFileMoveAssignment(
                            projectFile = currentFile,
                            finalFolder = previousKey.folder,
                            finalBaseName = previousKey.fileName.substringBeforeLast('.'),
                        )
                    },
                )
            } catch (rollbackError: ProjectFileMoveRollbackException) {
                return failedMove(
                    quarantineIncompleteMove(
                        source,
                        context,
                        initialPending,
                        inputBuffer,
                        rollbackError,
                    ),
                )
            } catch (rollbackError: Exception) {
                val failure = ProjectFileMoveRollbackException(
                    sourceKey = oldKey,
                    targetKey = moved.key,
                    restoredToSource = false,
                    affectedKeys = moveBatch.filesByPreviousKey.flatMapTo(mutableSetOf()) { (previousKey, file) ->
                        listOf(previousKey, file.key)
                    },
                    cause = rollbackError,
                )
                return failedMove(
                    quarantineIncompleteMove(
                        source,
                        context,
                        initialPending,
                        inputBuffer,
                        failure,
                    ),
                )
            }
            if (restoredBatch == null) {
                val failure = ProjectFileMoveRollbackException(
                    sourceKey = oldKey,
                    targetKey = moved.key,
                    restoredToSource = false,
                    affectedKeys = moveBatch.filesByPreviousKey.flatMapTo(mutableSetOf()) { (previousKey, file) ->
                        listOf(previousKey, file.key)
                    },
                    cause = readError,
                )
                return failedMove(
                    quarantineIncompleteMove(
                        source,
                        context,
                        initialPending,
                        inputBuffer,
                        failure,
                    ),
                )
            }
            val restoredFiles = moveBatch.filesByPreviousKey.mapValues { (_, currentFile) ->
                restoredBatch.filesByPreviousKey.getValue(currentFile.key)
            }
            restoredFiles.values.forEach(::replaceProjectFileHandle)
            restorePeerPending(restoredFiles)
            val restoredSource = restoredFiles.getValue(oldKey)
            pendingForFailedMove(restoredSource, context, initialPending, inputBuffer, localDiskBaseline)
                ?.let { pending ->
                    saveCoordinator.schedule(oldKey, pending)
                }
            return failedMove(readError.message ?: "이동한 파일을 확인하지 못해 원래 위치로 되돌렸습니다.")
        }
        val workspace = bookmarks.value.projectData
        val previousManagedTags = projectConfig.value
            ?.effectiveAutoTags(oldKey.folder.relativePath)
            .orEmpty()
        val updatedManagedTags = projectConfig.value
            ?.effectiveAutoTags(targetFolder.key.relativePath)
            .orEmpty()
        fun withTargetMetadata(note: NoteFile): NoteFile = note.withManagedTagChanges(
                projectName = bookmarks.value.projectData?.name.orEmpty(),
                previousTags = previousManagedTags,
                updatedTags = updatedManagedTags,
            )
            .withPlotStage(targetPlotStage)
        val sourcePath = workspaceLinkPath(source)
        fun rewrittenSource(note: NoteFile): NoteFile {
            val raw = note.inject()
            val rewritten = sourcePath?.let { moveBatch.references.rewriteSource(it, raw) } ?: raw
            return if (rewritten == raw) note else NoteFile.parse(rewritten)
        }
        var externalWonDuringMove = externalWonAtMoveStart ||
            movedDiskNote.inject() != rewrittenSource(sourceDiskBeforeMove).inject()

        // A move is not reported as fully successful until its managed tags/Plot value have been
        // written once. Input that arrives during this provider write remains in inputBuffer and is
        // rebased onto the committed metadata below.
        val beforeWritePending = saveCoordinator.cancel(oldKey) ?: initialPending
        var intendedNote = withTargetMetadata(
            if (externalWonDuringMove) movedDiskNote
            else (inputBuffer.noteFile ?: beforeWritePending?.noteFile)?.let(::rewrittenSource) ?: movedDiskNote,
        )
        var committedNote = movedDiskNote
        val semanticWriteError = if (intendedNote.inject() != movedDiskNote.inject()) {
            try {
                writeNote(moved.platformFile, intendedNote)
                committedNote = intendedNote
                null
            } catch (error: Exception) {
                error
            }
        } else null
        var movedModified = fileManager.lastModified(moved.platformFile)
        if (semanticWriteError == null) {
            val fresh = try {
                fileManager.readMarkdown(moved.platformFile)
            } catch (_: Exception) {
                movedModified = null
                null
            }
            if (fresh != null && fresh.inject() != committedNote.inject()) {
                externalWonDuringMove = true
                committedNote = fresh
                intendedNote = fresh
            }
        }
        if (workspace != null && semanticWriteError == null) {
            updateWorkspaceMetadataIndex(moved, committedNote, movedModified, context)
        }

        val modifiedByOldKey = moveBatch.filesByPreviousKey.mapValues { (previousKey, _) ->
            if (previousKey == oldKey) movedModified else knownModified[previousKey]
        }.toMutableMap()
        synchronizeRenamedReferences(moveBatch.references, excludedKeys = setOf(oldKey))
        moveBatch.filesByPreviousKey.forEach { (previousKey, file) ->
            if (previousKey != oldKey && moveBatch.references.sourceUpdates.any {
                    it.path == workspaceLinkPath(ProjectFile(previousKey, file.platformFile))
                }) modifiedByOldKey[previousKey] = knownModified[previousKey]
        }

        // No suspending work is allowed after this final drain. updateBody writes to inputBuffer
        // while the old key is in flight, so the latest editor value can only leave under newKey.
        val latePending = saveCoordinator.cancel(oldKey)
        val latePeerPendingByOldKey = moveBatch.filesByPreviousKey.keys
            .asSequence()
            .filter { it != oldKey }
            .mapNotNull { key -> saveCoordinator.cancel(key)?.let { key to it } }
            .toMap()
        val effectivePeerPending = latePeerPendingByOldKey
        val movedNote = if (externalWonDuringMove) intendedNote else withTargetMetadata(
            (inputBuffer.noteFile ?: latePending?.noteFile ?: beforeWritePending?.noteFile)
                ?.let(::rewrittenSource) ?: movedDiskNote,
        )
        val newKey = moved.key
        onFinalKey(newKey)
        val filesByPreviousKey = moveBatch.filesByPreviousKey
        val oldKeys = filesByPreviousKey.keys
        val current = _hierarchyState.value
        val selectedNewKey = current.selectedFileKey?.let { key -> filesByPreviousKey[key]?.key ?: key }
        val nextContents = hierarchyContentsAfterMove(
            current = current.folderContents,
            filesByPreviousKey = filesByPreviousKey,
            movedOldKey = oldKey,
            movedNote = movedNote,
        )

        folderFileSelectionMemory.renameFiles(filesByPreviousKey.mapValues { (_, file) -> file.key })
        selectedNewKey?.let(folderFileSelectionMemory::remember)

        val previousEditorSessions = oldKeys.mapNotNull { key ->
            editorSessionKeys[key]?.let { session -> key to session }
        }.toMap()
        oldKeys.forEach(editorSessionKeys::remove)
        filesByPreviousKey.forEach { (previousKey, file) ->
            if (previousKey != oldKey || !externalWonDuringMove) {
                previousEditorSessions[previousKey]?.let { session -> editorSessionKeys[file.key] = session }
            }
        }

        val previousLoadStates = _fileLoadStates.value.filterKeys { it in oldKeys }
        _fileLoadStates.value = _fileLoadStates.value.toMutableMap().apply {
            oldKeys.forEach(::remove)
            filesByPreviousKey.forEach { (previousKey, file) ->
                val state = if (previousKey == oldKey &&
                    (previousLoadStates[previousKey] != null || beforeWritePending != null ||
                        latePending != null || inputBuffer.noteFile != null)
                ) {
                    FileLoadUiState.Loaded(movedNote)
                } else {
                    effectivePeerPending[previousKey]?.noteFile?.let(FileLoadUiState::Loaded)
                        ?: previousLoadStates[previousKey]?.let { previous ->
                            if (previous is FileLoadUiState.Loading) {
                                FileLoadUiState.Error("파일 경로가 변경되었습니다. 문서를 다시 열어 주세요.")
                            } else previous
                        }
                }
                if (state != null) this[file.key] = state
            }
        }

        oldKeys.forEach { key ->
            knownProjectFiles.remove(key)
            knownModified.remove(key)
        }
        filesByPreviousKey.forEach { (previousKey, file) ->
            knownProjectFiles[file.key] = file
            if (previousKey !in effectivePeerPending) {
                modifiedByOldKey[previousKey]?.let { modified -> knownModified[file.key] = modified }
            }
        }
        val writeScheduled = semanticWriteError != null || movedNote.inject() != committedNote.inject()
        if (writeScheduled) {
            saveCoordinator.schedule(newKey, PendingWrite(moved, movedNote, committedNote, context))
        }
        if (writeScheduled) knownModified.remove(newKey) else movedModified?.let { knownModified[newKey] = it }
        effectivePeerPending.forEach { (previousKey, pending) ->
            val file = filesByPreviousKey.getValue(previousKey)
            saveCoordinator.schedule(file.key, pending.copy(projectFile = file))
        }

        val wasSelected = current.selectedFileKey == oldKey
        publishHierarchy(
            next = current.copy(
                folderContents = nextContents,
                currentFolderKey = if (wasSelected) targetFolder.key else current.currentFolderKey,
            ),
            preferredKey = selectedNewKey,
        )

        return semanticWriteError?.let { error ->
            val message = if (physicalMoveRequired) {
                "파일은 이동했지만 관리 태그·Plot 저장을 완료하지 못했습니다. 자동 저장을 다시 시도합니다."
            } else {
                "Plot 구분 저장을 완료하지 못했습니다. 자동 저장을 다시 시도합니다."
            }
            workspaceSaveCoordinator.reportAutoSaveFailure(newKey.relativePath, error)
            workspaceSessionCoordinator.reportError(message)
            message
        }
    }

    private fun hierarchyContentsAfterMove(
        current: Map<FolderKey, HierarchyFolderContent>,
        filesByPreviousKey: Map<FileKey, ProjectFile>,
        movedOldKey: FileKey,
        movedNote: NoteFile,
    ): Map<FolderKey, HierarchyFolderContent> {
        val oldKeys = filesByPreviousKey.keys
        val affectedFolders = buildSet {
            filesByPreviousKey.forEach { (oldKey, file) ->
                add(oldKey.folder)
                add(file.key.folder)
            }
        }
        val previousPlotEntries = current.values
            .flatMap(HierarchyFolderContent::plotEntries)
            .associateBy { it.projectFile.key }
        val refreshed = affectedFolders.associateWith { folderKey ->
            val config = folderConfig(folderKey)
            val retainedFiles = current[folderKey]?.files.orEmpty().filterNot { it.key in oldKeys }
            val movedFiles = filesByPreviousKey.values.filter { it.key.folder == folderKey }
            if (!config.isPlot) {
                HierarchyFolderContent(files = (retainedFiles + movedFiles).sortedFor(config))
            } else {
                val retainedEntries = current[folderKey]?.plotEntries.orEmpty()
                    .filterNot { it.projectFile.key in oldKeys }
                val movedEntries = filesByPreviousKey.mapNotNull { (previousKey, file) ->
                    if (file.key.folder != folderKey) return@mapNotNull null
                    if (previousKey == movedOldKey) {
                        PlotFileEntry(file, movedNote.plotStage, file.plotOrder())
                    } else {
                        previousPlotEntries[previousKey]?.copy(projectFile = file, order = file.plotOrder())
                    }
                }
                val entries = (retainedEntries + movedEntries).sortedForPlot()
                HierarchyFolderContent(files = entries.map(PlotFileEntry::projectFile), plotEntries = entries)
            }
        }
        return current + refreshed
    }

    private suspend fun pendingForFailedMove(
        source: ProjectFile,
        context: WorkspaceReadContext,
        initialPending: PendingWrite?,
        inputBuffer: FileMoveInputBuffer,
        expectedDiskAtMoveStart: NoteFile? = null,
    ): PendingWrite? {
        val scheduled = saveCoordinator.cancel(source.key)
        val disk = expectedDiskAtMoveStart?.let {
            runCatching { fileManager.readMarkdown(source.platformFile) }.getOrNull()
        }
        if (disk != null && disk.inject() != expectedDiskAtMoveStart.inject()) {
            if (activeFileMoveInputs[source.key] === inputBuffer) {
                activeFileMoveInputs.remove(source.key)
            }
            inputBuffer.noteFile = null
            acceptExternalDocument(source.key, source, disk, context)
            return null
        }
        val pending = inputBuffer.noteFile?.let { note ->
            PendingWrite(
                source,
                note,
                disk ?: scheduled?.expectedDisk ?: initialPending?.expectedDisk ?: note,
                context,
            )
        } ?: scheduled ?: initialPending
        return pending?.copy(projectFile = source, expectedDisk = disk ?: pending.expectedDisk)
    }

    /** Replaces a stale SAF handle without changing selection, ordering or loaded editor state. */
    private fun replaceProjectFileHandle(authoritative: ProjectFile) {
        knownProjectFiles[authoritative.key] = authoritative
        val current = _hierarchyState.value
        val content = current.folderContents[authoritative.key.folder] ?: return
        val files = content.files.map { file ->
            if (file.key == authoritative.key) authoritative else file
        }
        val plotEntries = content.plotEntries.map { entry ->
            if (entry.projectFile.key == authoritative.key) entry.copy(projectFile = authoritative) else entry
        }
        _hierarchyState.value = current.copy(
            folderContents = current.folderContents + (
                authoritative.key.folder to content.copy(files = files, plotEntries = plotEntries)
            ),
        )
    }

    private suspend fun quarantineIncompleteMove(
        source: ProjectFile,
        context: WorkspaceReadContext,
        initialPending: PendingWrite?,
        inputBuffer: FileMoveInputBuffer,
        error: ProjectFileMoveRollbackException,
    ): String {
        val referenceKeys = error.affectedPaths.filter {
            it.workspaceName == bookmarks.value.projectData?.name && it.workspaceKind == context.workspaceKind
        }.mapTo(mutableSetOf()) { FileKey.of(it.relativePath) }
        val conflictKeys = error.affectedKeys + referenceKeys
        bookmarks.value.projectData?.let { workspace ->
            fileManager.workspaceMetadataIndex.invalidate(
                workspace,
                WorkspaceKind.PROJECT,
                conflictKeys.toList(),
            )
        }
        val message = buildString {
            append(error.message ?: "파일 이동 복구 상태를 확인해야 합니다.")
            append(if (error.restoredToSource) " 원래 위치의 설정 상태를 확인해 주세요." else " 대상 위치의 파일 상태를 확인해 주세요.")
        }
        conflictKeys.forEach { key ->
            if (key != source.key) saveCoordinator.cancel(key)
            pageLoadJobs.remove(key)?.cancel()
            knownModified.remove(key)
        }
        _documentConflicts.value = _documentConflicts.value + conflictKeys.associateWith { message }
        val unresolved = pendingForFailedMove(source, context, initialPending, inputBuffer)
        if (unresolved != null) {
            saveCoordinator.schedule(source.key, unresolved)
        } else {
            workspaceSaveCoordinator.reportAutoSaveFailure(source.key.relativePath, error)
        }
        conflictKeys.forEach { key ->
            workspaceSaveCoordinator.reportAutoSaveFailure(key.relativePath, error)
        }
        workspaceSessionCoordinator.reportError(message)
        return message
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
        val result = try {
            fileManager.renameFileWithReferences(projectFile, newName)
        } catch (error: FileRenameRollbackException) {
            return quarantinePathChanges(error.affectedPaths, error)
        }
        if (result == null) {
            // A SAF rollback can return a new handle even when the original name is restored.
            runCatching { fileManager.findProjectFile(oldKey) }.getOrNull()?.let { restored ->
                replaceProjectFileHandle(restored)
                saveCoordinator.cancel(oldKey)?.let { pending ->
                    saveCoordinator.schedule(oldKey, pending.copy(projectFile = restored))
                }
            }
            return "파일 이름을 변경하지 못했습니다. 이름과 폴더 접근 권한을 확인해 주세요."
        }
        return WorkspaceLoadDiagnostics.measure("file-rename.post-provider") {
            val renamed = result.renamedFile.platformFile
            pageLoadJobs.remove(oldKey)?.cancel()
            val (targetSourceUpdated, targetModified) = WorkspaceLoadDiagnostics.measure("file-rename.target-metadata") {
                val updated = result.sourceUpdates.any { it.path == workspaceLinkPath(projectFile) }
                updated to if (updated) null else fileManager.lastModified(renamed)
            }
            synchronizeRenamedReferences(result.references)
            val (newKey, renamedProjectFile) = WorkspaceLoadDiagnostics.measure("file-rename.state-remap") {
                val renamedModified = if (targetSourceUpdated) knownModified[oldKey] else targetModified
                val newKey = oldKey.rename(renamed.name)
                folderFileSelectionMemory.renameFile(oldKey, newKey)
                moveEditorSessionKey(oldKey, newKey)
                val renamedProjectFile = ProjectFile(newKey, renamed)
                knownProjectFiles.remove(oldKey)
                knownProjectFiles[newKey] = renamedProjectFile
                // 다시 읽지 않고 캐시를 옮긴다. rename I/O 중 들어온 최신 pending도 함께 이동한다.
                val cached = loadedNote(oldKey)
                val pendingToMove = saveCoordinator.cancel(oldKey)
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
                newKey to renamedProjectFile
            }

            // cache와 editor session을 유지하고 목록·선택만 새 key로 바꾼다.
            val (nextHierarchy, preferredKey) = WorkspaceLoadDiagnostics.measure("file-rename.hierarchy-prepare") {
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
                current.copy(
                    folderContents = current.folderContents +
                        (oldKey.folder to HierarchyFolderContent(orderedFiles, plotEntries)),
                ) to if (selectedKey == oldKey) newKey else selectedKey
            }
            WorkspaceLoadDiagnostics.measure("file-rename.hierarchy-publish") {
                publishHierarchy(next = nextHierarchy, preferredKey = preferredKey)
            }
            WorkspaceLoadDiagnostics.measure("file-rename.bookmark") {
                runCatching { fileManager.pickFile(renamedProjectFile) }
            }
            null
        }
    }

    private fun quarantinePathChanges(paths: Set<WorkspaceLinkPath>, error: Exception): String {
        val workspace = bookmarks.value.projectData
        val keys = paths.filter {
            it.workspaceName == workspace?.name && it.workspaceKind == bookmarks.value.workspaceKind
        }.mapTo(mutableSetOf()) { FileKey.of(it.relativePath) }
        val message = error.message ?: "경로 변경 복구 상태를 확인해 주세요."
        keys.forEach { key ->
            saveCoordinator.cancel(key)
            pageLoadJobs.remove(key)?.cancel()
            knownModified.remove(key)
        }
        _documentConflicts.value += keys.associateWith { message }
        workspaceSessionCoordinator.reportError(message)
        return message
    }

    private suspend fun restoreOrderHandles(keys: List<FileKey>): Boolean {
        keys.forEach { key ->
            runCatching { fileManager.findProjectFile(key) }.getOrNull()?.let { restored ->
                replaceProjectFileHandle(restored)
                saveCoordinator.cancel(key)?.let { pending ->
                    saveCoordinator.schedule(key, pending.copy(projectFile = restored))
                }
            }
        }
        return false
    }

    private suspend fun synchronizeRenamedReferences(
        result: WorkspaceLinkRewriteReceipt,
        excludedKeys: Set<FileKey> = emptySet(),
    ) {
        val trace = WorkspaceLoadDiagnostics.start("references.synchronize")
        var workspaceNameQueries = 0
        try {
            WorkspaceLoadDiagnostics.within(trace) {
                val context = currentWorkspaceReadContext()
                val workspace = bookmarks.value.projectData ?: return@within
                // One provider name query per reconciliation, only when a path actually needs it.
                val workspaceName by lazy(LazyThreadSafetyMode.NONE) {
                    workspaceNameQueries++
                    readWorkspaceName(workspace)
                }
                val updates = result.sourceUpdates.filter {
                    it.path.workspaceName == workspaceName && it.path.workspaceKind == context.workspaceKind &&
                        FileKey.of(it.path.relativePath) !in excludedKeys
                }.associateBy { it.path }
                // The provider may change a source while the post-rename index is rebuilding.
                val observed = WorkspaceLoadDiagnostics.measure("references.observe-sources") {
                    updates.mapValues { (_, update) ->
                        try {
                            val modified = fileManager.lastModified(update.file)
                            fileManager.readMarkdown(update.file) to modified
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                WorkspaceLoadDiagnostics.measure("references.publish-loaded-sources") {
                    updates.values.forEach { update ->
                        val key = FileKey.of(update.path.relativePath)
                        pageLoadJobs.remove(key)?.cancel()
                        if (_fileLoadStates.value[key] is FileLoadUiState.Loading) {
                            putLoadedNote(key, observed[update.path]?.first ?: NoteFile.parse(update.after))
                            knownProjectFiles[key] = ProjectFile(key, update.file)
                        }
                    }
                }
                // All provider reads finish before draining pending values: typing during I/O stays intact.
                WorkspaceLoadDiagnostics.measure("references.rebase-loop") {
                    knownProjectFiles.values.toList().forEach { file ->
                        if (file.key in excludedKeys) return@forEach
                        val name = workspaceName
                        val path = runCatching {
                            WorkspaceLinkPath(context.workspaceKind, name, file.key.relativePath)
                        }.getOrNull() ?: return@forEach
                        val update = updates[path]
                        val pending = saveCoordinator.cancel(file.key)
                        val original = pending?.noteFile ?: loadedNote(file.key) ?: return@forEach
                        val disk = update?.let { observed[path]?.first ?: NoteFile.parse(it.after) }
                        val modified = observed[path]?.second
                        if (update != null && (disk?.inject() != NoteFile.parse(update.after).inject() ||
                            pending != null && pending.expectedDisk.inject() != NoteFile.parse(update.before).inject())
                        ) {
                            // External edits win, as in acceptExternalDocument; only incomplete rollback quarantines writes.
                            pageLoadJobs.remove(file.key)?.cancel()
                            editorSessionKeys.remove(file.key)
                            putLoadedNote(file.key, checkNotNull(disk))
                            knownProjectFiles[file.key] = ProjectFile(file.key, update.file)
                            knownModified.remove(file.key)
                            modified?.let { knownModified[file.key] = it }
                            return@forEach
                        }
                        val raw = original.inject()
                        val rewritten = if (pending == null && disk != null) disk else {
                            val replacement = result.rewriteSource(path, raw)
                            if (replacement == raw) original else NoteFile.parse(replacement)
                        }
                        if (loadedNote(file.key) != null) putLoadedNote(file.key, rewritten)
                        val authoritative = update?.let { ProjectFile(file.key, it.file) } ?: file
                        knownProjectFiles[file.key] = authoritative
                        if (update != null) knownModified.remove(file.key)
                        modified?.let { knownModified[file.key] = it }
                        if (pending != null) {
                            saveCoordinator.schedule(file.key, pending.copy(
                                projectFile = authoritative,
                                noteFile = rewritten,
                                expectedDisk = disk ?: pending.expectedDisk,
                            ))
                        }
                    }
                }
            }
            trace.complete("workspaceNameQueries=$workspaceNameQueries")
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
    }

    private suspend fun refreshFoldersAndFiles(
        project: PlatformFile,
        preferredKey: FileKey?,
        cancelRemoved: Boolean,
        preferredFolderKey: FolderKey? = null,
        refreshGeneralSources: Boolean = false,
        refreshProjectPlotMetadata: Boolean = false,
        useInitialProjectIndexSnapshot: Boolean = false,
    ) {
        val context = currentWorkspaceReadContext()
        if (context.projectLocation != project.toString() || !isCurrentWorkspace(context)) return
        val previous = _hierarchyState.value
        val indexedHierarchy = if (useInitialProjectIndexSnapshot) {
            initialProjectHierarchySnapshot(project, context)
        } else {
            null
        }
        if (!isCurrentWorkspace(context)) return
        val folders = indexedHierarchy?.folders ?: fileManager.listFolders(project)
        val config = indexedHierarchy?.projectConfig ?: projectConfig.value
        val contents = loadHierarchyFolderContents(
            folders,
            config,
            context,
            refreshProjectPlotMetadata,
            indexedHierarchy?.filesByFolder,
        )
        if (!isCurrentWorkspace(context)) return
        val hierarchyFilesChanged = previous.folderContents.values
            .flatMap { content -> content.files }
            .map { file -> file.key to file.platformFile.toString() }
            .toSet() != contents.values
            .flatMap { content -> content.files }
            .map { file -> file.key to file.platformFile.toString() }
            .toSet()

        if (context.workspaceKind == WorkspaceKind.GENERAL) {
            reconcileGeneralSourceFiles(contents.values.flatMap(HierarchyFolderContent::files))
        }

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
            diagnosticReason = WorkspaceLoadDiagnostics.reason(),
            diagnosticTriggerOp = WorkspaceLoadDiagnostics.operationId(),
        )
        if (context.workspaceKind == WorkspaceKind.PROJECT) appliedHierarchyConfig = config
        if (indexedHierarchy != null) workspaceSessionCoordinator.markIndexedHierarchyPublished(context)

        // File names are useful without source grouping. Publish the hierarchy first so a General
        // workspace with hundreds of files never waits for frontmatter indexing before it appears.
        if (context.workspaceKind == WorkspaceKind.GENERAL) {
            if (refreshGeneralSources || hierarchyFilesChanged) {
                scheduleGeneralSourceRefresh(
                    project = project,
                    context = context,
                      files = contents.values.flatMap(HierarchyFolderContent::files),
                      refreshMetadata = refreshGeneralSources || useInitialProjectIndexSnapshot,
                )
            }
        } else {
            generalSourceRefreshJob?.cancel()
            generalSourceRefreshJob = null
            pendingGeneralSourceRefresh = null
            _generalSourceState.value = null
        }
    }

    private suspend fun initialProjectHierarchySnapshot(
        project: PlatformFile,
        context: WorkspaceReadContext,
    ): ProjectHierarchySnapshot? {
        if (context.workspaceKind != WorkspaceKind.PROJECT || initialHierarchySnapshotAttempt?.matches(context) == true) {
            return null
        }
        initialHierarchySnapshotAttempt = context
        return fileManager.awaitProjectHierarchySnapshot(project)
            ?.takeIf { snapshot ->
                snapshot.projectLocation == context.projectLocation && isCurrentWorkspace(context)
            }
    }

    private fun scheduleGeneralSourceRefresh(
        project: PlatformFile,
        context: WorkspaceReadContext,
        files: List<ProjectFile>,
        refreshMetadata: Boolean,
    ) {
        if (generalSourceRefreshJob?.isActive == true) {
            pendingGeneralSourceRefresh = PendingGeneralSourceRefresh(project, context, files,
                refreshMetadata || pendingGeneralSourceRefresh?.refreshMetadata == true)
            WorkspaceLoadDiagnostics.event(
                "general-source-refresh.queued",
                "generation=${context.generation}|files=${files.size}",
            )
            return
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val trace = WorkspaceLoadDiagnostics.begin(
                "general-source-refresh",
                "generation=${context.generation}|files=${files.size}",
            )
            try {
                val sources = fileManager.generalSources.load(project, files, refreshMetadata)
                if (isCurrentWorkspace(context)) {
                    _generalSourceState.value = sources.withFiles(currentHierarchyFiles())
                    trace.complete(
                        "groups=${sources.groups.size}|entries=${sources.files.size}|published=true",
                    )
                } else {
                    trace.complete("published=false|reason=workspace-changed")
                }
            } catch (cancellation: CancellationException) {
                trace.fail(cancellation)
                throw cancellation
            } catch (error: Exception) {
                trace.fail(error)
                if (isCurrentWorkspace(context)) {
                    workspaceSessionCoordinator.reportError(
                        error.message ?: "General 구분 정보를 불러오지 못했습니다.",
                    )
                }
            } finally {
                val running = currentCoroutineContext().job
                if (generalSourceRefreshJob === running) {
                    generalSourceRefreshJob = null
                    val pending = pendingGeneralSourceRefresh
                    pendingGeneralSourceRefresh = null
                    if (pending != null && isCurrentWorkspace(pending.context)) {
                        scheduleGeneralSourceRefresh(pending.project, pending.context, pending.files, pending.refreshMetadata)
                    }
                }
            }
        }
        generalSourceRefreshJob = job
        job.start()
    }

    /** Keep the already-rendered General grouping aligned with the latest provider file list. */
    private fun reconcileGeneralSourceFiles(files: List<ProjectFile>) {
        val previous = _generalSourceState.value ?: return
        _generalSourceState.value = previous.withFiles(files)
    }

    private fun currentHierarchyFiles(): List<ProjectFile> =
        _hierarchyState.value.folderContents.values.flatMap(HierarchyFolderContent::files)

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
        diagnosticReason: String = "hierarchy-change",
        diagnosticTriggerOp: Long? = null,
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
        _hierarchyState.value = next.copy(
            selectedFileKey = selected?.key,
            isLoaded = true,
        )
    }

    private fun scheduleWorkspaceLinkIndexRefresh(vault: PlatformFile, reason: String = "vault-change", triggerOp: Long? = null) {
        val identity = vault.toString()
        if (workspaceLinkIndexRefreshJob?.isActive == true && workspaceLinkIndexRefreshVault == identity) {
            workspaceLinkIndexRefreshPendingVault = identity
            return
        }
        val generation = ++workspaceLinkIndexRefreshGeneration
        workspaceLinkIndexRefreshJob?.cancel()
        workspaceLinkIndexRefreshVault = identity
        workspaceLinkIndexRefreshPendingVault = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val refreshTrace = WorkspaceLoadDiagnostics.begin("link-index-refresh", "reason=$reason|triggerOp=${triggerOp ?: 0}")
                WorkspaceLoadDiagnostics.within(refreshTrace, reason) {
                    try {
                        fileReconciliationMutex.withLock {
                            if (generation != workspaceLinkIndexRefreshGeneration ||
                                bookmarks.value.vaultData?.toString() != identity) return@withLock
                            fileManager.rebuildWorkspaceLinkIndex(vault)
                        }
                        refreshTrace.complete()
                    } catch (error: Throwable) { refreshTrace.fail(error); throw error }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (generation != workspaceLinkIndexRefreshGeneration ||
                    bookmarks.value.vaultData?.toString() != identity) return@launch
                WorkspaceLoadDiagnostics.event(
                    "workspace-link-index.failed",
                    "error=${error::class.simpleName}",
                )
            } finally {
                if (workspaceLinkIndexRefreshJob === currentCoroutineContext().job &&
                    generation == workspaceLinkIndexRefreshGeneration
                ) {
                    val rerun = workspaceLinkIndexRefreshPendingVault == identity
                    workspaceLinkIndexRefreshPendingVault = null
                    workspaceLinkIndexRefreshJob = null
                    workspaceLinkIndexRefreshVault = null
                    if (rerun) scheduleWorkspaceLinkIndexRefresh(vault, "coalesced", triggerOp)
                }
            }
        }
        workspaceLinkIndexRefreshJob = job
        job.start()
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
        references: WorkspaceLinkRewriteReceipt,
    ) {
        val keyChanges = updates.associate { it.oldKey to it.projectFile.key }
        val oldKeys = keyChanges.keys
        oldKeys.forEach { pageLoadJobs.remove(it)?.cancel() }
        synchronizeRenamedReferences(references)
        // Finish every provider read before draining input or publishing keys that may be exchanged.
        val pendingByOldKey = oldKeys.mapNotNull { key -> saveCoordinator.cancel(key)?.let { key to it } }.toMap()
        val updatedReferenceKeys = references.sourceUpdates.filter {
            it.path.workspaceName == bookmarks.value.projectData?.name &&
                it.path.workspaceKind == bookmarks.value.workspaceKind
        }.mapTo(mutableSetOf()) { FileKey.of(it.path.relativePath) }
        // A renamed handle's latest mtime cannot certify an old cached body.
        val modified = oldKeys.associateWith { key -> knownModified[key] }
        val selectedNewKey = selectedOldKey?.let { keyChanges[it] ?: it }
        folderFileSelectionMemory.renameFiles(keyChanges)
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
                val previous = previousLoadStates[update.oldKey]
                val latest = pendingByOldKey[update.oldKey]?.noteFile
                    ?: (previous as? FileLoadUiState.Loaded)?.noteFile
                val note = latest?.let { current ->
                    if (update.noteFile != null && update.oldKey !in updatedReferenceKeys)
                        current.withPlotStage(update.noteFile.plotStage) else current
                } ?: update.noteFile
                val nextState = note?.let(FileLoadUiState::Loaded)
                    ?: if (previous is FileLoadUiState.Loading) {
                        FileLoadUiState.Error("파일 경로가 변경되었습니다. 문서를 다시 열어 주세요.")
                    } else previous
                if (nextState != null) this[update.projectFile.key] = nextState
            }
        }
        oldKeys.forEach { oldKey ->
            knownProjectFiles.remove(oldKey)
            knownModified.remove(oldKey)
        }
        updates.forEach { update ->
            knownProjectFiles[update.projectFile.key] = update.projectFile
            val pending = pendingByOldKey[update.oldKey]
            if (pending != null) {
                val note = if (update.noteFile != null && update.oldKey !in updatedReferenceKeys)
                    pending.noteFile.withPlotStage(update.noteFile.plotStage)
                    else pending.noteFile
                saveCoordinator.schedule(update.projectFile.key, pending.copy(
                    projectFile = update.projectFile,
                    noteFile = note,
                    expectedDisk = if (update.oldKey in updatedReferenceKeys) pending.expectedDisk
                        else update.noteFile ?: pending.expectedDisk,
                ))
            } else modified[update.oldKey]?.let { value ->
                knownModified[update.projectFile.key] = value
            }
        }

        val current = _hierarchyState.value
        val files = current.folderContents[folder.key]?.files.orEmpty().filterNot { it.key in oldKeys } +
            updates.map(OrderStateUpdate::projectFile)
        val config = folderConfig(folder.key)
        val content = if (config.isPlot) {
            val entries = files.map { file ->
                val loaded = loadedNote(file.key)
                val update = updates.firstOrNull { it.projectFile.key == file.key }
                val stage = when {
                    loaded != null -> loaded.plotStage
                    update != null -> update.noteFile?.plotStage
                    else -> current.folderContents[folder.key]?.plotEntries?.firstOrNull { it.projectFile.key == file.key }?.stage
                }
                PlotFileEntry(file, stage, file.plotOrder())
            }.sortedForPlot()
            HierarchyFolderContent(entries.map(PlotFileEntry::projectFile), entries)
        } else HierarchyFolderContent(files.sortedFor(config))
        publishHierarchy(
            next = current.copy(folderContents = current.folderContents + (folder.key to content)),
            preferredKey = if (current.currentFolderKey == folder.key) selectedNewKey else current.selectedFileKey,
        )
    }

    private fun clearProjectState(preserveBaseline: Boolean = false) {
        initialHierarchySnapshotAttempt = null
        appliedHierarchyConfig = null
        workspaceSessionCoordinator.onProjectStateCleared()
        _documentConflicts.value = emptyMap()
        pendingPropertyDefinitionSyncs.clear()
        latestPropertyTypeRevision.clear()
        latestPropertyMembershipRevision.clear()
        propertyDefinitionRevision = 0L
        _propertyDefinitionSyncUiState.value = emptyMap()
        _generalSourceState.value = null
        committedFolderSettings = null
        incompleteCreation = null
        _canRetryCreation.value = false
        commitSessionCoordinator.clear(preserveBaseline)
        saveCoordinator.cancelAll()
        pageLoadJobs.values.forEach(Job::cancel)
        pageLoadJobs.clear()
        generalSourceRefreshJob?.cancel()
        generalSourceRefreshJob = null
        pendingGeneralSourceRefresh = null
        _hierarchyState.value = HierarchyUiState()
        _pendingFolderDeletion.value = null
        _pendingFileTrash.value = null
        fileTrashPreparationInProgress = false
        _fileLoadStates.value = emptyMap()
        knownModified.clear()
        knownProjectFiles.clear()
        editorSessionKeys.clear()
        folderFileSelectionMemory.clear()
    }

    private suspend fun loadHierarchyFolderContents(
        folders: List<ProjectFolder>,
        config: ProjectConfig?,
        context: WorkspaceReadContext,
        refreshProjectPlotMetadata: Boolean = false,
        filesByFolder: Map<FolderKey, List<ProjectFile>>? = null,
    ): Map<FolderKey, HierarchyFolderContent> = folders.associate { folder ->
        folder.key to loadFolderContent(
            folder,
            config,
            context,
            refreshProjectPlotMetadata,
            filesByFolder?.get(folder.key),
        )
    }

    private suspend fun loadFolderContent(
        folder: ProjectFolder,
        config: ProjectConfig? = projectConfig.value,
        context: WorkspaceReadContext = currentWorkspaceReadContext(),
        refreshProjectPlotMetadata: Boolean = false,
        indexedFiles: List<ProjectFile>? = null,
    ): HierarchyFolderContent {
        val folderConfig = folderConfig(folder.key, config, context)
        val files = indexedFiles ?: fileManager.listProjectFiles(folder)
        if (!folderConfig.isPlot) {
            if (!isCurrentWorkspace(context)) throw CancellationException("작업 공간이 변경되었습니다.")
            return HierarchyFolderContent(files = files.sortedFor(folderConfig))
        }
        val plotEntries = loadPlotEntries(
            folder,
            files,
            context,
            refreshProjectPlotMetadata,
        ).sortedForPlot()
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
        refreshMetadata: Boolean = true,
    ): List<PlotFileEntry> {
        val workspace = bookmarks.value.projectData
            ?.takeIf { it.toString() == context.projectLocation }
            ?: throw CancellationException("작업 공간이 변경되었습니다.")
        val projectFiles = files ?: fileManager.listProjectFiles(folder)
        val snapshot = fileManager.workspaceMetadataIndex
            .snapshot(workspace, context.workspaceKind)
            ?: throw CancellationException("작업 공간이 변경되었습니다.")
        val cached = snapshot.entries
        val permits = Semaphore(PROJECT_PLOT_SCAN_PARALLELISM)
        val results = coroutineScope {
            projectFiles.map { projectFile ->
                async {
                    permits.withPermit {
                        currentCoroutineContext().ensureActive()
                        val previous = cached[projectFile.key]
                        val modifiedAt = if (refreshMetadata || previous == null ||
                            previous.file.platformFile.toString() != projectFile.platformFile.toString()) {
                            fileManager.lastModified(projectFile.platformFile)
                        } else {
                            previous.modifiedAt
                        }
                        val pending = saveCoordinator.pendingValue(projectFile.key)?.noteFile
                        val loaded = loadedNote(projectFile.key)
                        val hasPendingWrite = saveCoordinator.hasPending(projectFile.key)
                        val loadedMatchesDisk = knownProjectFiles[projectFile.key]?.platformFile?.toString() ==
                            projectFile.platformFile.toString() && (!refreshMetadata ||
                            modifiedAt == null || modifiedAt <= 0L ||
                            (previous?.modifiedAtVerified != false && knownModified[projectFile.key] == modifiedAt))
                        val cachedPlot = previous
                            ?.takeIf { item ->
                                if (refreshMetadata) item.isFresh(projectFile, modifiedAt)
                                else item.file.platformFile.toString() == projectFile.platformFile.toString()
                            }
                            ?.plot
                            ?.takeIf(IndexedPlot::readSucceeded)
                        var readSucceeded = true
                        var refreshedLoadedNote: NoteFile? = null
                        val stage = when {
                            pending != null -> pending.plotStage
                            loaded != null && (loadedMatchesDisk || hasPendingWrite) -> loaded.plotStage
                            cachedPlot != null -> cachedPlot.stage
                            else -> try {
                                fileManager.readMarkdown(projectFile.platformFile).let { note ->
                                    if (loaded != null && !hasPendingWrite) refreshedLoadedNote = note
                                    note.plotStage
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                readSucceeded = false
                                if (loaded == null && isCurrentWorkspace(context)) {
                                    setFileLoadState(
                                        projectFile.key,
                                        FileLoadUiState.Error(error.message ?: "파일을 읽지 못했습니다."),
                                    )
                                }
                                loaded?.plotStage
                            }
                        }
                        val freshPrevious = previous?.takeIf { item ->
                            if (refreshMetadata) item.isFresh(projectFile, modifiedAt)
                            else item.file.platformFile.toString() == projectFile.platformFile.toString()
                        }
                        PlotIndexResult(
                            metadata = WorkspaceFileMetadata(
                                file = projectFile,
                                modifiedAt = if (hasPendingWrite && !loadedMatchesDisk) {
                                    previous?.modifiedAt
                                } else if (freshPrevious != null) {
                                    freshPrevious.modifiedAt
                                } else {
                                    modifiedAt
                                },
                                source = freshPrevious?.source,
                                plot = IndexedPlot(stage, readSucceeded),
                                noteFile = refreshedLoadedNote ?: freshPrevious?.noteFile,
                                projectMetadataVerified = refreshedLoadedNote == null &&
                                    freshPrevious?.projectMetadataVerified == true,
                                modifiedAtVerified = if (refreshedLoadedNote != null) true
                                    else freshPrevious?.modifiedAtVerified ?: true,
                            ),
                            entry = PlotFileEntry(
                                projectFile = projectFile,
                                stage = stage,
                                order = projectFile.plotOrder(),
                            ),
                            refreshedLoadedNote = refreshedLoadedNote,
                            loadedNoteAtScanStart = loaded,
                            observedModifiedAt = modifiedAt,
                        )
                    }
                }
            }.awaitAll()
        }
        if (!isCurrentWorkspace(context)) throw CancellationException("작업 공간이 변경되었습니다.")
        val settledResults = results.map { result ->
            val key = result.metadata.key
            val currentLoaded = loadedNote(key)
            val editorChangedDuringScan = saveCoordinator.hasPending(key) ||
                currentLoaded !== result.loadedNoteAtScanStart
            when {
                editorChangedDuringScan -> {
                    val currentStage = currentLoaded?.plotStage
                        ?: result.loadedNoteAtScanStart?.plotStage
                        ?: snapshot.entries[key]?.plot?.stage
                    result.copy(
                        metadata = result.metadata.copy(
                            modifiedAt = snapshot.entries[key]?.modifiedAt,
                            plot = IndexedPlot(currentStage),
                        ),
                        entry = result.entry.copy(stage = currentStage),
                        refreshedLoadedNote = null,
                    )
                }
                result.refreshedLoadedNote != null -> {
                    putLoadedNote(key, result.refreshedLoadedNote)
                    result.observedModifiedAt?.let { modified -> knownModified[key] = modified }
                    result
                }
                else -> result
            }
        }
        fileManager.workspaceMetadataIndex.reconcileFolder(
            workspace,
            context.workspaceKind,
            folder.key,
            snapshot,
            settledResults.map(PlotIndexResult::metadata),
        )
        return settledResults.map(PlotIndexResult::entry)
    }

    private fun loadedNote(fileKey: FileKey): NoteFile? =
        (_fileLoadStates.value[fileKey] as? FileLoadUiState.Loaded)?.noteFile

    private fun putLoadedNote(fileKey: FileKey, noteFile: NoteFile) {
        setFileLoadState(fileKey, FileLoadUiState.Loaded(noteFile))
    }

    private fun setFileLoadState(fileKey: FileKey, state: FileLoadUiState) {
        _fileLoadStates.update { it + (fileKey to state) }
    }

    private suspend fun updateWorkspaceMetadataIndex(
        projectFile: ProjectFile,
        noteFile: NoteFile,
        modifiedAt: Long?,
        context: WorkspaceReadContext,
        expectedIdentity: com.ninetag.machum.external.WorkspaceMetadataIdentity? = null,
    ) {
        if (!isCurrentWorkspace(context)) return
        val workspace = bookmarks.value
        val root = workspace.projectData
            ?.takeIf { it.toString() == context.projectLocation }
            ?: return
        val source = if (context.workspaceKind == WorkspaceKind.GENERAL) {
            GeneralSourceProperty.read(noteFile.inject()).let { IndexedGeneralSource(it.value, it.error) }
        } else null
        val plot = if (context.workspaceKind == WorkspaceKind.PROJECT) {
            IndexedPlot(noteFile.plotStage)
        } else null
        if (!isCurrentWorkspace(context)) return
        fileManager.workspaceMetadataIndex.put(
            root,
            context.workspaceKind,
            WorkspaceFileMetadata(projectFile, modifiedAt, source, plot, noteFile),
            expectedIdentity,
        )
    }

    /** A selected General document is authoritative for its own source entry. */
    private fun updateGeneralSourceEntry(
        projectFile: ProjectFile,
        noteFile: NoteFile,
        context: WorkspaceReadContext,
    ) {
        if (context.workspaceKind != WorkspaceKind.GENERAL || !isCurrentWorkspace(context)) return
        val previous = _generalSourceState.value ?: return
        val parsed = GeneralSourceProperty.read(noteFile.inject())
        val entry = GeneralSourceEntry(projectFile, parsed.value, parsed.error)
        val hadEntry = previous.files.any { it.file.key == projectFile.key }
        val files = if (hadEntry) {
            previous.files.map { current -> if (current.file.key == projectFile.key) entry else current }
        } else {
            previous.files + entry
        }
        _generalSourceState.value = previous.withSourceEntries(files)
    }

    private fun moveEditorSessionKey(oldKey: FileKey, newKey: FileKey) {
        if (oldKey == newKey) return
        val sessionKey = editorSessionKeys.remove(oldKey) ?: return
        editorSessionKeys[newKey] = sessionKey
    }

    private fun currentWorkspaceReadContext(): WorkspaceReadContext =
        workspaceSessionCoordinator.currentContext(bookmarks.value)

    private fun isCurrentWorkspace(context: WorkspaceReadContext): Boolean =
        workspaceSessionCoordinator.isCurrentWorkspace(bookmarks.value, context)

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

    /** The refresh owns this same lock, so joining it must happen before entering the lock. */
    private suspend fun <T> withMutationIndexGate(
        context: WorkspaceReadContext,
        fallback: T,
        action: suspend () -> T,
    ): T {
        try {
            while (isCurrentWorkspace(context)) {
                val vault = bookmarks.value.vaultData
                if (vault == null) return fileReconciliationMutex.withLock {
                    if (isCurrentWorkspace(context)) action() else fallback
                }
                while (true) {
                    val refresh = workspaceLinkIndexRefreshJob
                        ?.takeIf { workspaceLinkIndexRefreshVault == vault.toString() }
                    refresh?.join()
                    if (!isCurrentWorkspace(context)) return fallback
                    if (refresh === workspaceLinkIndexRefreshJob || workspaceLinkIndexRefreshJob == null) break
                }
                val prepared = fileManager.awaitWorkspaceLinkIndexPreparation(vault)
                var entered = false
                val result = fileReconciliationMutex.withLock {
                    if (!isCurrentWorkspace(context) || !fileManager.workspaceLinkIndex.isCurrent(prepared)) fallback
                    else {
                        entered = true
                        action()
                    }
                }
                if (entered) return result
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (isCurrentWorkspace(context)) workspaceSessionCoordinator.reportError(
                error.message ?: "링크 인덱스를 준비하지 못해 파일 작업을 중단했습니다.",
            )
        }
        return fallback
    }

    private suspend fun runWorkspaceMutation(
        project: PlatformFile? = bookmarks.value.projectData,
        context: WorkspaceReadContext = currentWorkspaceReadContext(),
        reportErrors: Boolean = true,
        action: suspend (PlatformFile, WorkspaceReadContext) -> Unit,
    ) {
        if (workspaceInputBlocked || project == null) return
        withMutationIndexGate(context, Unit) withLock@ {
            if (!isCurrentWorkspace(context)) return@withLock
            workspaceSaveCoordinator.runAfterFlush {
                if (isCurrentWorkspace(context)) {
                    bookmarks.value.vaultData?.let { vault ->
                        check(fileManager.workspaceLinkIndex.readyPreparation(vault.toString()) != null) {
                            "저장 후 링크 인덱스를 확인하지 못해 파일 작업을 중단했습니다."
                        }
                    }
                    action(project, context)
                }
            }.onFailure { error ->
                if (reportErrors && isCurrentWorkspace(context) && workspaceSaveCoordinator.lastErrorMessage.value == null) {
                    workspaceSessionCoordinator.reportError(error.message ?: "파일 작업을 완료하지 못했습니다.")
                }
            }
        }
    }

    private fun launchNavigation(action: suspend (isLatest: () -> Boolean) -> Unit) {
        if (workspaceInputBlocked) return
        val generation = workspaceSessionCoordinator.captureGeneration()
        val request = navigationGate.newRequest()
        viewModelScope.launch {
            // 파일 선택 bookmark와 순서 변경 rename이 서로의 오래된 경로를 덮어쓰지 않도록
            // 탐색도 외부 변경·rename과 같은 임계 구역에서 직렬화한다.
            fileReconciliationMutex.withLock {
                if (workspaceInputBlocked || !workspaceSessionCoordinator.isCurrentGeneration(generation)) return@withLock
                navigationGate.run(request) { latest ->
                    action {
                        latest() && !workspaceInputBlocked &&
                            workspaceSessionCoordinator.isCurrentGeneration(generation)
                    }
                }
            }
        }
    }

    companion object {
        // Phase 2 폴링 주기. 활성(포커스) 상태에서만 동작.
        private const val SAVE_DEBOUNCE_MS = 500L
    }
}

internal data class WorkspaceReadContext(
    val projectLocation: String?,
    val workspaceKind: WorkspaceKind,
    val vaultLocation: String?,
    val generation: Long,
) {
    fun matches(other: WorkspaceReadContext): Boolean = this == other
}

private data class PendingPropertyDefinitionSync(
    val context: WorkspaceReadContext,
    val changes: List<VersionedPropertyDefinitionChange>,
)

private data class VersionedPropertyDefinitionChange(
    val change: DocumentPropertyDefinitionChange,
    val revision: Long,
    val membership: Pair<String, String>? = null,
    val typeKey: String? = null,
)

private fun String.toFileKeyOrNull(): FileKey? =
    runCatching { FileKey.of(this) }.getOrNull()

internal data class PendingWrite(
    val projectFile: ProjectFile,
    val noteFile: NoteFile,
    val expectedDisk: NoteFile,
    val context: WorkspaceReadContext,
)

private class FileMoveInputBuffer(
    var noteFile: NoteFile? = null,
)

data class FileMoveResult(
    val finalKey: FileKey,
    val errorMessage: String? = null,
)

private data class PendingGeneralSourceRefresh(
    val project: PlatformFile,
    val context: WorkspaceReadContext,
    val files: List<ProjectFile>,
    val refreshMetadata: Boolean,
)

private fun GeneralSourceState.withFiles(currentFiles: List<ProjectFile>): GeneralSourceState {
    val previousByKey = files.associateBy { it.file.key }
    return withSourceEntries(
        currentFiles.map { file ->
            previousByKey[file.key]
                ?.takeIf { it.file.platformFile.toString() == file.platformFile.toString() }
                ?.copy(file = file)
                ?: GeneralSourceEntry(file = file, value = null)
        },
    )
}

/** Display groups are always configured groups plus values currently referenced by files. */
private fun GeneralSourceState.withSourceEntries(updatedFiles: List<GeneralSourceEntry>): GeneralSourceState = copy(
    groups = if (enabled) {
        (configuredGroups + updatedFiles.mapNotNull { it.value?.takeIf(String::isNotEmpty) })
            .distinct()
            .sorted()
    } else {
        emptyList()
    },
    files = updatedFiles,
)

private data class PlotIndexResult(
    val metadata: WorkspaceFileMetadata,
    val entry: PlotFileEntry,
    val refreshedLoadedNote: NoteFile? = null,
    val loadedNoteAtScanStart: NoteFile? = null,
    val observedModifiedAt: Long? = null,
)

private data class OrderStateUpdate(
    val oldKey: FileKey,
    val projectFile: ProjectFile,
    val noteFile: NoteFile? = null,
)

data class PropertyDefinitionSyncUiState(
    val message: String? = null,
    val isRetrying: Boolean = false,
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

internal data class ProjectFileMoveNamePlan(
    val projectFile: ProjectFile,
    val finalFolder: FolderKey,
    val finalBaseName: String,
)

/**
 * Computes the exact file names for a placement move without touching storage.
 *
 * Numbered source groups are compacted, while numbered targets keep their current order and append
 * the moved document. General folders have no managed numbering and retain the current file name.
 */
internal fun projectFileMoveNamePlan(
    source: ProjectFile,
    sourceContent: HierarchyFolderContent,
    sourceConfig: FolderConfig,
    sourcePlotStage: PlotStage?,
    targetFolder: FolderKey,
    targetContent: HierarchyFolderContent,
    targetConfig: FolderConfig,
    targetPlotStage: PlotStage?,
): List<ProjectFileMoveNamePlan> {
    val plans = linkedMapOf<FileKey, ProjectFileMoveNamePlan>()

    fun assign(file: ProjectFile, folder: FolderKey, baseName: String) {
        plans[file.key] = ProjectFileMoveNamePlan(file, folder, baseName)
    }

    if (sourceConfig.isPlot) {
        if (sourcePlotStage != null) {
            sourceContent.plotEntries
                .asSequence()
                .filter { entry -> entry.stage == sourcePlotStage && entry.projectFile.key != source.key }
                .sortedWith(compareBy<PlotFileEntry> { it.order == null }
                    .thenBy { it.order ?: Int.MAX_VALUE }
                    .thenBy { it.projectFile.key.fileName.lowercase() })
                .forEachIndexed { index, entry ->
                    assign(
                        entry.projectFile,
                        source.key.folder,
                        sourcePlotStage.fileName(index + PlotStage.FIRST_ORDER, entry.title),
                    )
                }
        }
    } else if (sourceConfig.type == FolderType.DEFAULT) {
        val startAt = if (source.key.folder == FolderKey.Base) 0 else 1
        sourceContent.files
            .filter { file -> file.key != source.key && file.defaultOrderPrefix() != null }
            .sortedFor(sourceConfig)
            .forEachIndexed { index, file ->
                assign(file, source.key.folder, "${startAt + index}. ${file.defaultOrderTitle()}")
            }
    }

    val sourceBaseName = source.platformFile.nameWithoutExtension
    val movedTitle = if (sourceConfig.type == FolderType.DEFAULT && (!sourceConfig.isPlot || sourcePlotStage != null)) {
        if (!sourceConfig.isPlot) source.defaultOrderTitle()
        else source.plotTitle()
    } else {
        sourceBaseName
    }
    when {
        targetConfig.isPlot && targetPlotStage != null -> {
            val targetEntries = targetContent.plotEntries
                .asSequence()
                .filter { entry -> entry.stage == targetPlotStage && entry.projectFile.key != source.key }
                .sortedWith(compareBy<PlotFileEntry> { it.order == null }
                    .thenBy { it.order ?: Int.MAX_VALUE }
                    .thenBy { it.projectFile.key.fileName.lowercase() })
                .toList()
            targetEntries.forEachIndexed { index, entry ->
                assign(
                    entry.projectFile,
                    targetFolder,
                    targetPlotStage.fileName(index + PlotStage.FIRST_ORDER, entry.title),
                )
            }
            assign(
                source,
                targetFolder,
                targetPlotStage.fileName(targetEntries.size + PlotStage.FIRST_ORDER, movedTitle),
            )
        }
        targetConfig.type == FolderType.DEFAULT -> {
            val startAt = if (targetFolder == FolderKey.Base) 0 else 1
            val targetFiles = targetContent.files
                .filter { file -> file.key != source.key && file.defaultOrderPrefix() != null }
                .sortedFor(targetConfig)
                .toList()
            targetFiles.forEachIndexed { index, file ->
                assign(file, targetFolder, "${startAt + index}. ${file.defaultOrderTitle()}")
            }
            assign(source, targetFolder, "${startAt + targetFiles.size}. $movedTitle")
        }
        else -> assign(source, targetFolder, sourceBaseName)
    }

    return plans.values.filter { plan ->
        plan.projectFile.key == source.key ||
            plan.finalFolder != plan.projectFile.key.folder ||
            "${plan.finalBaseName}.md" != plan.projectFile.key.fileName
    }
}

/** 폴더별 목록과 선택 key만 저장한다. 편집 영역도 사이드바와 같은 snapshot에서 파생한다. */
data class HierarchyUiState(
    val folderList: List<ProjectFolder> = emptyList(),
    val folderContents: Map<FolderKey, HierarchyFolderContent> = emptyMap(),
    val currentFolderKey: FolderKey? = null,
    val selectedFileKey: FileKey? = null,
    val isLoaded: Boolean = false,
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
    val isCompleted: Boolean = false,
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
    val messageEdit: CommitMessageEditUiState? = null,
    val errorMessage: String? = null,
)

data class CommitMessageEditUiState(
    val commitId: String,
    val message: String,
    val originalMessage: String,
    val isSaving: Boolean = false,
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

internal fun resolveInternalDocumentLink(
    hierarchy: HierarchyUiState,
    rawTarget: String,
): FileKey? {
    val target = rawTarget
        .substringBefore('#')
        .substringBefore('|')
        .trim()
        .replace('\\', '/')
        .removePrefix("./")
        .removePrefix("/")
        .removeMarkdownExtension()
    if (target.isEmpty() || target.split('/').any { it == ".." }) return null

    val files = hierarchy.folderContents.values.flatMap(HierarchyFolderContent::files)
    if ('/' in target) {
        return files.firstOrNull { file ->
            file.key.relativePath.removeMarkdownExtension().equals(target, ignoreCase = true)
        }?.key
    }

    val sourceFolder = hierarchy.selectedFileKey?.folder ?: hierarchy.currentFolderKey
    return files
        .asSequence()
        .filter { file -> file.key.fileName.removeMarkdownExtension().equals(target, ignoreCase = true) }
        .sortedWith(
            compareByDescending<ProjectFile> { it.key.folder == sourceFolder }
                .thenBy { it.key.relativePath.lowercase() },
        )
        .firstOrNull()
        ?.key
}

private fun String.removeMarkdownExtension(): String =
    if (endsWith(".md", ignoreCase = true)) dropLast(3) else this
