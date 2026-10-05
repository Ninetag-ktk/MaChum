package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.external.Bookmarks
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.external.WorkspaceLoadDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/** Owns workspace navigation state, identity generations, and focus-driven refresh lifetime. */
internal class WorkspaceSessionCoordinator(
    private val scope: CoroutineScope,
    private val fileManager: FileManager,
    private val reconciliationMutex: Mutex,
    private val saveCoordinator: WorkspaceSaveCoordinator,
    private val operationBusy: () -> Boolean,
    private val invalidateNavigation: () -> Unit,
    private val clearProjectState: () -> Unit,
    private val checkExternalChanges: suspend (refreshHierarchy: Boolean) -> Unit,
) {
    private val _transitionError = MutableStateFlow<String?>(null)
    val transitionError: StateFlow<String?> = _transitionError.asStateFlow()

    private val _workspaceSelectionVisible = MutableStateFlow(false)
    val workspaceSelectionVisible: StateFlow<Boolean> = _workspaceSelectionVisible.asStateFlow()

    private val _vaultSelectionVisible = MutableStateFlow(false)
    val vaultSelectionVisible: StateFlow<Boolean> = _vaultSelectionVisible.asStateFlow()

    private val _workspaceSelectionPending = MutableStateFlow(false)
    val workspaceSelectionPending: StateFlow<Boolean> = _workspaceSelectionPending.asStateFlow()

    private val active = MutableStateFlow(WindowFocusSignal(focused = false, revision = 0L))
    private var activeProjectLocation: String? = null
    private var activeWorkspaceKind: WorkspaceKind? = null
    private var activeVaultLocation: String? = null
    private var generation = 0L
    private var focusRevision = 0L
    private var workspaceActivationFocusRevision = 0L
    private var skipInitialActiveHierarchyRefresh: WorkspaceReadContext? = null

    val inputBlocked: Boolean
        get() = _workspaceSelectionPending.value || _workspaceSelectionVisible.value

    val openingWorkspaceSelection: Boolean
        get() = _workspaceSelectionPending.value

    fun captureGeneration(): Long = generation

    fun isCurrentGeneration(value: Long): Boolean = value == generation

    init {
        scope.launch {
            active.collectLatest { signal ->
                if (!signal.focused) return@collectLatest
                var firstCheck = true
                var skipFirstHierarchyRefresh = consumeInitialActiveHierarchyRefreshSkip()
                while (true) {
                    try {
                        val refreshHierarchy = firstCheck && !skipFirstHierarchyRefresh
                        skipFirstHierarchyRefresh = false
                        if (refreshHierarchy) {
                            WorkspaceLoadDiagnostics.measure("hierarchy-refresh", "focus-return") {
                                checkExternalChanges(refreshHierarchy)
                            }
                        } else checkExternalChanges(refreshHierarchy)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        if (active.value == signal) reportError(error.message ?: "외부 파일 변경을 확인하지 못했습니다.")
                    }
                    firstCheck = false
                    delay(POLL_INTERVAL_MS.milliseconds)
                }
            }
        }
    }

    fun setActive(focused: Boolean) {
        focusRevision += 1
        if (!focused) skipInitialActiveHierarchyRefresh = null
        active.value = WindowFocusSignal(focused, focusRevision)
    }

    fun currentContext(bookmarks: Bookmarks): WorkspaceReadContext = WorkspaceReadContext(
        projectLocation = bookmarks.projectData?.toString(),
        workspaceKind = bookmarks.workspaceKind,
        vaultLocation = bookmarks.vaultData?.toString(),
        generation = generation,
    )

    fun isCurrentWorkspace(bookmarks: Bookmarks, context: WorkspaceReadContext): Boolean =
        activeProjectLocation == context.projectLocation &&
            context.generation == generation &&
            bookmarks.vaultData?.toString() == context.vaultLocation &&
            activeWorkspaceKind == context.workspaceKind &&
            bookmarks.projectData?.toString() == context.projectLocation &&
            bookmarks.workspaceKind == context.workspaceKind

    fun updateActiveVault(location: String?): Boolean {
        if (activeVaultLocation == location) return false
        activeVaultLocation = location
        return location != null
    }

    fun hasActiveProject(): Boolean = activeProjectLocation != null

    fun isWorkspaceChanged(projectLocation: String, kind: WorkspaceKind): Boolean =
        activeProjectLocation != projectLocation || activeWorkspaceKind != kind

    fun activateWorkspace(projectLocation: String, kind: WorkspaceKind) {
        activeProjectLocation = projectLocation
        activeWorkspaceKind = kind
    }

    fun clearActiveWorkspace() {
        activeProjectLocation = null
        activeWorkspaceKind = null
    }

    fun onProjectStateCleared() {
        skipInitialActiveHierarchyRefresh = null
        workspaceActivationFocusRevision = focusRevision
    }

    fun markIndexedHierarchyPublished(context: WorkspaceReadContext) {
        if (focusRevision == workspaceActivationFocusRevision) {
            skipInitialActiveHierarchyRefresh = context
        }
    }

    fun openWorkspaceSelection() {
        if (inputBlocked || operationBusy()) return
        val vault = fileManager.bookmarks.value.vaultData?.toString() ?: return
        _workspaceSelectionPending.value = true
        val requestGeneration = generation
        invalidateNavigation()
        scope.launch {
            try {
                reconciliationMutex.withLock {
                    saveCoordinator.runAfterFlush {
                        check(requestGeneration == generation && fileManager.bookmarks.value.vaultData?.toString() == vault) {
                            "작업 공간이 변경되었습니다."
                        }
                        check(!operationBusy()) { "진행 중인 작업이 끝난 뒤 다시 시도해 주세요." }
                        showWorkspaceSelection()
                    }.onFailure { error ->
                        if (saveCoordinator.lastErrorMessage.value == null) reportError(error.message)
                    }
                }
            } finally {
                _workspaceSelectionPending.value = false
            }
        }
    }

    private fun showWorkspaceSelection() {
        generation++
        clearProjectState()
        clearActiveWorkspace()
        _vaultSelectionVisible.value = false
        _workspaceSelectionVisible.value = true
    }

    fun openVaultSelection() {
        val bookmarks = fileManager.bookmarks.value
        if (openingWorkspaceSelection || operationBusy() ||
            fileManager.workspaceOpenRequest.value != null ||
            (!_workspaceSelectionVisible.value && bookmarks.projectData != null)) return
        generation++
        invalidateNavigation()
        _workspaceSelectionVisible.value = true
        _vaultSelectionVisible.value = true
    }

    fun completeVaultSelection() {
        if (fileManager.bookmarks.value.vaultData == null) return
        generation++
        _vaultSelectionVisible.value = false
        _workspaceSelectionVisible.value = true
    }

    fun beginWorkspaceCompletion(projectLocation: String, kind: WorkspaceKind) {
        activateWorkspace(projectLocation, kind)
    }

    fun finishWorkspaceCompletion() {
        invalidateNavigation()
        _vaultSelectionVisible.value = false
        _workspaceSelectionVisible.value = false
    }

    suspend fun <T> runSelectionAction(action: suspend () -> T): Result<T> {
        val vault = fileManager.bookmarks.value.vaultData?.toString()
        val requestGeneration = generation
        return reconciliationMutex.withLock {
            saveCoordinator.runAfterFlush {
                check(!openingWorkspaceSelection && !operationBusy()) {
                    "진행 중인 작업이 끝난 뒤 다시 시도해 주세요."
                }
                check(requestGeneration == generation && vault == fileManager.bookmarks.value.vaultData?.toString()) {
                    "작업 공간이 변경되었습니다."
                }
                check(
                    !_vaultSelectionVisible.value &&
                        (_workspaceSelectionVisible.value || fileManager.bookmarks.value.projectData == null),
                ) { "작업 공간 선택 화면을 다시 열어 주세요." }
                action()
            }
        }
    }

    suspend fun confirmWorkspaceOpen(
        kind: WorkspaceKind,
        completeSelection: suspend () -> Unit,
    ): Result<Unit> {
        val request = fileManager.workspaceOpenRequest.value
            ?: return Result.failure(IllegalStateException("폴더 사용 방식 선택을 다시 열어 주세요."))
        if (openingWorkspaceSelection || operationBusy() || request.busy || _vaultSelectionVisible.value) {
            return Result.failure(IllegalStateException("진행 중인 작업이 끝난 뒤 다시 시도해 주세요."))
        }
        val vault = fileManager.bookmarks.value.vaultData?.toString()
        val requestGeneration = generation
        _workspaceSelectionPending.value = true
        invalidateNavigation()
        try {
            return reconciliationMutex.withLock {
                saveCoordinator.runAfterFlush {
                    check(!operationBusy()) { "진행 중인 작업이 끝난 뒤 다시 시도해 주세요." }
                    check(
                        requestGeneration == generation &&
                            vault == fileManager.bookmarks.value.vaultData?.toString() &&
                            fileManager.workspaceOpenRequest.value === request,
                    ) { "작업 공간이 변경되었습니다. 폴더를 다시 선택해 주세요." }
                    fileManager.confirmWorkspaceOpen(kind)
                    if (fileManager.workspaceOpenRequest.value == null) completeSelection()
                }
            }
        } finally {
            _workspaceSelectionPending.value = false
        }
    }

    fun launchTransition(action: suspend () -> Unit) {
        if (inputBlocked || operationBusy()) return
        val requestGeneration = generation
        val vault = fileManager.bookmarks.value.vaultData?.toString()
        val operationTrace = WorkspaceLoadDiagnostics.begin("workspace-transition", "reason=workspace-transition")
        val lockTrace = WorkspaceLoadDiagnostics.begin("workspace-transition-lock", "triggerOp=${operationTrace.operation?.id}")
        _workspaceSelectionPending.value = true
        invalidateNavigation()
        scope.launch {
            try {
                WorkspaceLoadDiagnostics.within(operationTrace, "workspace-transition") {
                reconciliationMutex.withLock {
                    lockTrace.complete()
                    val flushTrace = WorkspaceLoadDiagnostics.start("workspace-transition-flush")
                    try {
                        saveCoordinator.runAfterFlush {
                            flushTrace.complete()
                            check(!operationBusy()) { "진행 중인 작업이 끝난 뒤 다시 시도해 주세요." }
                            check(requestGeneration == generation && vault == fileManager.bookmarks.value.vaultData?.toString()) {
                                "작업 공간이 변경되었습니다."
                            }
                            action()
                        }.onFailure { error ->
                            flushTrace.fail(error)
                            if (saveCoordinator.lastErrorMessage.value == null) {
                                reportError(error.message ?: "작업 공간을 전환하지 못했습니다.")
                            }
                        }
                    } catch (error: Throwable) {
                        flushTrace.fail(error)
                        throw error
                    }
                }
                }
            } catch (error: Throwable) {
                lockTrace.fail(error)
                operationTrace.fail(error)
                throw error
            } finally {
                _workspaceSelectionPending.value = false
                operationTrace.complete()
            }
        }
    }

    fun ensureWorkspaceSelectionVisible() {
        showWorkspaceSelection()
    }

    fun reportError(message: String?) {
        _transitionError.value = message
    }

    fun clearError() {
        _transitionError.value = null
    }

    private fun consumeInitialActiveHierarchyRefreshSkip(): Boolean {
        val pending = skipInitialActiveHierarchyRefresh
        skipInitialActiveHierarchyRefresh = null
        return pending != null && isCurrentWorkspace(fileManager.bookmarks.value, pending)
    }

    private data class WindowFocusSignal(
        val focused: Boolean,
        val revision: Long,
    )

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
    }
}
