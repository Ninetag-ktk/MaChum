package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.CommitFileSide
import com.ninetag.machum.commit.FileRestoreResult
import com.ninetag.machum.commit.ProjectCommitService
import com.ninetag.machum.commit.RestoreSessionStaleException
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.external.WorkspaceLoadDiagnostics
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns commit workspace state and request lifetimes; editor reconciliation stays with MainViewModel. */
internal class CommitSessionCoordinator(
    private val scope: CoroutineScope,
    private val service: ProjectCommitService,
    private val reconciliationMutex: Mutex,
    private val saveCoordinator: DebouncedSaveCoordinator<FileKey, PendingWrite>,
    private val workspace: () -> CommitWorkspaceContext,
    private val isCurrentWorkspace: (WorkspaceReadContext) -> Boolean,
    private val restoreSelection: () -> CommitRestoreSelection,
    private val applyProjectRestore: suspend (PlatformFile, CommitRestoreSelection) -> Unit,
    private val applyFileRestore: suspend (PlatformFile, FileRestoreResult, CommitRestoreSelection) -> Unit,
    private val reloadSelectedFile: suspend (WorkspaceReadContext) -> Unit,
) {
    private val _createUiState = MutableStateFlow(CommitCreateUiState())
    val createUiState: StateFlow<CommitCreateUiState> = _createUiState.asStateFlow()

    private val _historyUiState = MutableStateFlow(CommitHistoryUiState())
    val historyUiState: StateFlow<CommitHistoryUiState> = _historyUiState.asStateFlow()

    private val _baselineUiState = MutableStateFlow<ProjectBaselineUiState>(ProjectBaselineUiState.Idle)
    val baselineUiState: StateFlow<ProjectBaselineUiState> = _baselineUiState.asStateFlow()

    private var readJob: Job? = null
    private var baselineJob: Job? = null
    private var requestGeneration = 0L

    init {
        WorkspaceLoadDiagnostics.event("commit-history.diagnostics-ready")
    }

    fun isBusy(): Boolean =
        _baselineUiState.value is ProjectBaselineUiState.Preparing ||
            _createUiState.value.isCommitting ||
            _historyUiState.value.messageEdit?.isSaving == true ||
            _historyUiState.value.restore?.isRestoring == true

    fun ensureInitialBaseline() {
        val snapshot = workspace()
        if (snapshot.inputBlocked || snapshot.kind != WorkspaceKind.PROJECT) return
        val project = snapshot.project ?: return
        val location = project.toString()
        when (val state = _baselineUiState.value) {
            is ProjectBaselineUiState.Preparing -> if (state.projectLocation == location) return
            is ProjectBaselineUiState.Ready -> if (state.projectLocation == location) return
            else -> Unit
        }

        baselineJob?.cancel()
        _baselineUiState.value = ProjectBaselineUiState.Preparing(location)
        val context = snapshot.context
        baselineJob = scope.launch {
            try {
                reconciliationMutex.withLock {
                    if (workspace().context != context) return@withLock
                    saveCoordinator.flushAll()
                    if (workspace().context != context) return@withLock
                    service.ensureInitialBaseline(project)
                }
                if (workspace().context == context) {
                    _baselineUiState.value = ProjectBaselineUiState.Ready(location)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (workspace().context == context) {
                    _baselineUiState.value = ProjectBaselineUiState.Error(
                        projectLocation = location,
                        message = error.message ?: "초기 기준점을 만들지 못했습니다.",
                    )
                }
            }
        }
    }

    fun skipInitialBaseline() {
        val snapshot = workspace()
        val project = snapshot.project ?: return
        if (snapshot.kind != WorkspaceKind.PROJECT) return
        baselineJob?.cancel()
        baselineJob = null
        _baselineUiState.value = ProjectBaselineUiState.Ready(project.toString(), skipped = true)
    }

    fun openChanges() {
        if (_historyUiState.value.messageEdit != null) return
        val snapshot = workspace()
        if (!snapshot.canStartProjectOperation()) return
        val currentCreate = _createUiState.value
        if (currentCreate.isOpen && currentCreate.errorMessage == null) return
        if (_historyUiState.value.restore?.isRestoring == true) return
        val project = snapshot.project ?: return
        val context = snapshot.context
        val request = beginRequest()
        _historyUiState.value = _historyUiState.value.copy(
            isOpen = false,
            isLoading = false,
            diff = null,
            restore = null,
            errorMessage = null,
        )
        _createUiState.value = currentCreate.copy(
            isOpen = true,
            isLoading = true,
            isCommitting = false,
            isCompleted = false,
            preview = null,
            diff = null,
            errorMessage = null,
        )
        readJob = scope.launch {
            try {
                val preview = reconciliationMutex.withLock {
                    saveCoordinator.flushAll()
                    if (!isCurrentRequest(request, context)) return@withLock null
                    service.preview(project)
                } ?: return@launch
                if (!isCurrentRequest(request, context)) return@launch
                _createUiState.value = _createUiState.value.copy(isLoading = false, preview = preview)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentRequest(request, context)) return@launch
                _createUiState.value = _createUiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "변경 사항을 확인하지 못했습니다.",
                )
            }
        }
    }

    fun dismissWorkspace() {
        if (_historyUiState.value.messageEdit != null) {
            dismissMessageEdit()
            return
        }
        if (_createUiState.value.isCommitting || _historyUiState.value.restore?.isRestoring == true) return
        cancelRequests()
        _createUiState.value = CommitCreateUiState()
        _historyUiState.value = CommitHistoryUiState()
    }

    fun updateMessage(message: String) {
        val state = _createUiState.value
        if (!state.isOpen || state.isCommitting) return
        _createUiState.value = state.copy(message = message)
    }

    fun openChangesDiff(change: CommitChange) {
        val state = _createUiState.value
        if (!state.isOpen || state.isLoading || state.isCommitting) return
        val snapshot = workspace()
        val project = snapshot.project ?: return
        val context = snapshot.context
        val request = beginRequest()
        val target = CommitDiffUiState(null, change.fileId, change.displayPath, isLoading = true)
        _createUiState.value = state.copy(diff = target, errorMessage = null)
        readJob = scope.launch {
            try {
                val result = reconciliationMutex.withLock {
                    saveCoordinator.flushAll()
                    if (!isCurrentRequest(request, context)) return@withLock null
                    service.diff(project, null, change.fileId)
                } ?: return@launch
                if (!isCurrentRequest(request, context)) return@launch
                val current = _createUiState.value
                if (current.diff?.matches(target) == true) {
                    _createUiState.value = current.copy(diff = target.copy(isLoading = false, result = result))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentRequest(request, context)) return@launch
                val current = _createUiState.value
                if (current.diff?.matches(target) == true) {
                    _createUiState.value = current.copy(
                        diff = target.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "파일 diff를 읽지 못했습니다.",
                        ),
                    )
                }
            }
        }
    }

    fun closeChangesDiff() {
        if (_createUiState.value.isCommitting) return
        cancelRequests()
        _createUiState.value = _createUiState.value.copy(diff = null)
    }

    fun openHistory() {
        WorkspaceLoadDiagnostics.event("commit-history.open")
        if (_historyUiState.value.messageEdit != null) return
        val snapshot = workspace()
        if (!snapshot.canStartProjectOperation()) return
        if (_historyUiState.value.isOpen || _createUiState.value.isCommitting) {
            WorkspaceLoadDiagnostics.event("commit-history.skipped", "reason=already-open-or-committing")
            return
        }
        _createUiState.value = _createUiState.value.copy(
            isOpen = false,
            isLoading = false,
            diff = null,
            errorMessage = null,
        )
        loadHistory(_historyUiState.value.selectedCommitId)
    }

    fun retryHistory() {
        if (_historyUiState.value.messageEdit != null) return
        if (!_historyUiState.value.isOpen) return
        loadHistory(_historyUiState.value.selectedCommitId)
    }

    private fun loadHistory(selectedCommitId: String?) {
        val snapshot = workspace()
        if (snapshot.pendingPropertyDefinitionSync) return
        val project = snapshot.project ?: return
        val context = snapshot.context
        val request = beginRequest()
        val historyTrace = WorkspaceLoadDiagnostics.begin("commit-history", "generation=$request")
        val dispatchTrace = WorkspaceLoadDiagnostics.begin(
            "commit-history.dispatch", "triggerOp=${historyTrace.operation?.id}",
        )
        _historyUiState.value = _historyUiState.value.copy(
            isOpen = true,
            isLoading = true,
            selectedCommitId = selectedCommitId,
            diff = null,
            restore = null,
            errorMessage = null,
        )
        readJob = scope.launch {
            dispatchTrace.complete()
            try {
                val loaded = WorkspaceLoadDiagnostics.time("commit-history.lock", historyTrace) { lockTrace ->
                    reconciliationMutex.withLock {
                        lockTrace.complete()
                        WorkspaceLoadDiagnostics.time("commit-history.flush", historyTrace) {
                            saveCoordinator.flushAll()
                        }
                        if (!isCurrentRequest(request, context)) return@withLock null
                        val preview = WorkspaceLoadDiagnostics.time("commit-history.preview", historyTrace) {
                            service.preview(project)
                        }
                        val history = WorkspaceLoadDiagnostics.time("commit-history.read", historyTrace) {
                            service.historySummary(project)
                        }
                        preview to history
                    }
                } ?: return@launch
                val (preview, history) = loaded
                if (!isCurrentRequest(request, context)) return@launch
                WorkspaceLoadDiagnostics.time("commit-history.publish", historyTrace) {
                    _historyUiState.value = CommitHistoryUiState(
                        isOpen = true,
                        history = history,
                        workingPreview = preview,
                        selectedCommitId = selectedCommitId?.takeIf { selected ->
                            history.any { it.commit.id == selected }
                        },
                    )
                }
                // State publication is not a rendered-frame measurement; root timings are inclusive.
                historyTrace.complete("outcome=published|count=${history.size}")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                historyTrace.fail(error)
                if (!isCurrentRequest(request, context)) return@launch
                _historyUiState.value = _historyUiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "커밋 이력을 읽지 못했습니다.",
                )
            }
        }.also { job ->
            // Also closes traces if the coroutine is cancelled before its first instruction.
            job.invokeOnCompletion { error ->
                if (error != null) {
                    dispatchTrace.fail(error)
                    historyTrace.fail(error)
                } else {
                    historyTrace.complete("outcome=discarded")
                }
            }
        }
    }

    fun selectHistoryEntry(commitId: String) {
        val state = _historyUiState.value
        if (state.messageEdit != null) return
        if (!state.isOpen || state.isLoading || state.restore?.isRestoring == true) return
        _historyUiState.value = state.copy(selectedCommitId = commitId, diff = null, errorMessage = null)
    }

    fun openHistoryDiff(commitId: String, change: CommitChange) {
        val state = _historyUiState.value
        if (state.messageEdit != null) return
        if (!state.isOpen || state.isLoading || state.restore?.isRestoring == true) return
        val snapshot = workspace()
        val project = snapshot.project ?: return
        val context = snapshot.context
        val request = beginRequest()
        val target = CommitDiffUiState(commitId, change.fileId, change.displayPath, isLoading = true)
        _historyUiState.value = state.copy(selectedCommitId = commitId, diff = target, errorMessage = null)
        readJob = scope.launch {
            try {
                val result = service.diff(project, commitId, change.fileId)
                if (!isCurrentRequest(request, context)) return@launch
                val current = _historyUiState.value
                if (current.diff?.matches(target) == true) {
                    _historyUiState.value = current.copy(diff = target.copy(isLoading = false, result = result))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentRequest(request, context)) return@launch
                val current = _historyUiState.value
                if (current.diff?.matches(target) == true) {
                    _historyUiState.value = current.copy(
                        diff = target.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "파일 diff를 읽지 못했습니다.",
                        ),
                    )
                }
            }
        }
    }

    fun navigateBackFromHistory() {
        val state = _historyUiState.value
        if (state.messageEdit != null) {
            dismissMessageEdit()
            return
        }
        if (state.restore?.isRestoring == true) return
        when {
            state.restore != null -> _historyUiState.value = state.copy(restore = null)
            state.diff != null -> {
                cancelRequests()
                _historyUiState.value = state.copy(diff = null)
            }
            state.selectedCommitId != null -> _historyUiState.value = state.copy(selectedCommitId = null)
            else -> dismissWorkspace()
        }
    }

    fun openMessageEdit(commitId: String) {
        val state = _historyUiState.value
        if (!state.canRequestRestore() || state.restore != null || state.messageEdit != null || state.diff?.isLoading == true) return
        val entry = state.history.firstOrNull { it.commit.id == commitId } ?: return
        _historyUiState.value = state.copy(messageEdit = CommitMessageEditUiState(commitId, entry.displayMessage, entry.displayMessage))
    }

    fun updateEditedMessage(message: String) {
        val state = _historyUiState.value
        val edit = state.messageEdit ?: return
        if (edit.isSaving) return
        _historyUiState.value = state.copy(messageEdit = edit.copy(message = message, errorMessage = null))
    }

    fun dismissMessageEdit() {
        val state = _historyUiState.value
        if (state.messageEdit?.isSaving == true) return
        _historyUiState.value = state.copy(messageEdit = null)
    }

    fun saveEditedMessage() {
        val snapshot = workspace()
        if (!snapshot.canStartProjectOperation()) return
        val state = _historyUiState.value
        val edit = state.messageEdit ?: return
        if (edit.isSaving || edit.message.isBlank()) return
        val project = snapshot.project ?: return
        val request = beginRequest()
        _historyUiState.value = state.copy(messageEdit = edit.copy(isSaving = true, errorMessage = null))
        scope.launch {
            try {
                val message = reconciliationMutex.withLock {
                    if (!isCurrentRequest(request, snapshot.context)) return@withLock null
                    service.updateCommitMessage(project, edit.commitId, edit.message, edit.originalMessage)
                } ?: return@launch
                if (!isCurrentRequest(request, snapshot.context)) return@launch
                val current = _historyUiState.value
                _historyUiState.value = current.copy(
                    history = current.history.map { if (it.commit.id == edit.commitId) it.copy(displayMessage = message) else it },
                    messageEdit = null,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentRequest(request, snapshot.context)) return@launch
                _historyUiState.value = _historyUiState.value.copy(
                    messageEdit = edit.copy(errorMessage = error.message ?: "커밋 메시지를 저장하지 못했습니다."),
                )
            }
        }
    }

    fun requestProjectRestore(entry: com.ninetag.machum.commit.CommitHistoryEntry) {
        if (workspace().pendingPropertyDefinitionSync) return
        val state = _historyUiState.value
        if (!state.canRequestRestore()) return
        val target = if (state.workingPreview?.parentCommitId == entry.commit.id) {
            CommitRestoreTarget.HeadSnapshot(entry)
        } else {
            CommitRestoreTarget.Project(entry)
        }
        _historyUiState.value = state.copy(
            restore = CommitRestoreUiState(target, state.workingPreview?.workingTreeHash ?: return),
            errorMessage = null,
        )
    }

    fun requestHeadRevert(entry: com.ninetag.machum.commit.CommitHistoryEntry) {
        if (workspace().pendingPropertyDefinitionSync) return
        val state = _historyUiState.value
        if (!state.canRequestRestore()) return
        if (entry.commit.parentId == null || state.history.firstOrNull()?.commit?.id != entry.commit.id) return
        _historyUiState.value = state.copy(
            restore = CommitRestoreUiState(
                CommitRestoreTarget.HeadChanges(entry),
                state.workingPreview?.workingTreeHash ?: return,
            ),
            errorMessage = null,
        )
    }

    fun requestFileRestore(change: CommitChange, side: CommitFileSide, contentOnly: Boolean) {
        if (workspace().pendingPropertyDefinitionSync) return
        val state = _historyUiState.value
        val commitId = state.selectedCommitId ?: return
        if (!state.canRequestRestore()) return
        val target = if (contentOnly) {
            CommitRestoreTarget.FileContent(commitId, change, side)
        } else {
            CommitRestoreTarget.File(commitId, change, side)
        }
        _historyUiState.value = state.copy(
            restore = CommitRestoreUiState(target, state.workingPreview?.workingTreeHash ?: return),
            errorMessage = null,
        )
    }

    fun dismissRestore() {
        val state = _historyUiState.value
        if (state.restore?.isRestoring == true) return
        _historyUiState.value = state.copy(restore = null)
    }

    fun confirmRestore() {
        val snapshot = workspace()
        if (!snapshot.canStartProjectOperation()) return
        val state = _historyUiState.value
        val restore = state.restore ?: return
        if (restore.isRestoring) return
        val project = snapshot.project ?: return
        val context = snapshot.context
        val request = beginRequest()
        _historyUiState.value = state.copy(restore = restore.copy(isRestoring = true, errorMessage = null))
        scope.launch {
            var restoreApplied = false
            try {
                val refreshed = reconciliationMutex.withLock {
                    if (!isCurrentRequest(request, context)) return@withLock null
                    check(!workspace().pendingPropertyDefinitionSync) {
                        "기본 속성 설정 저장을 완료한 뒤 복원해 주세요."
                    }
                    saveCoordinator.flushAll()
                    if (!isCurrentRequest(request, context)) return@withLock null

                    saveCoordinator.withWritesPaused {
                        val selection = restoreSelection()
                        when (val target = restore.target) {
                            is CommitRestoreTarget.Project -> {
                                service.restore(project, target.entry.commit.id, restore.expectedWorkingTreeHash)
                                restoreApplied = true
                                applyProjectRestore(project, selection)
                            }
                            is CommitRestoreTarget.HeadSnapshot -> {
                                service.restoreHeadSnapshot(project, target.entry.commit.id, restore.expectedWorkingTreeHash)
                                restoreApplied = true
                                applyProjectRestore(project, selection)
                            }
                            is CommitRestoreTarget.HeadChanges -> {
                                service.revertHead(project, target.entry.commit.id, restore.expectedWorkingTreeHash)
                                restoreApplied = true
                                applyProjectRestore(project, selection)
                            }
                            is CommitRestoreTarget.FileContent -> {
                                val result = service.restoreFileContent(
                                    project, target.commitId, target.change.fileId, target.side,
                                    restore.expectedWorkingTreeHash,
                                )
                                restoreApplied = true
                                applyFileRestore(project, result, selection)
                            }
                            is CommitRestoreTarget.File -> {
                                val result = service.restoreFile(
                                    project, target.commitId, target.change.fileId, target.side,
                                    restore.expectedWorkingTreeHash,
                                )
                                restoreApplied = true
                                applyFileRestore(project, result, selection)
                            }
                        }
                        if (!isCurrentRequest(request, context)) return@withWritesPaused null
                        reloadSelectedFile(context)
                        service.preview(project) to service.historySummary(project)
                    }
                } ?: return@launch

                if (!isCurrentRequest(request, context)) return@launch
                _historyUiState.value = _historyUiState.value.copy(
                    isLoading = false,
                    history = refreshed.second,
                    workingPreview = refreshed.first,
                    restore = null,
                    errorMessage = null,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentRequest(request, context)) return@launch
                if (error is RestoreSessionStaleException) {
                    val refreshed = runCatching {
                        reconciliationMutex.withLock {
                            if (!isCurrentRequest(request, context)) return@withLock null
                            service.preview(project) to service.historySummary(project)
                        }
                    }.getOrNull()
                    if (!isCurrentRequest(request, context)) return@launch
                    val current = _historyUiState.value
                    _historyUiState.value = current.copy(
                        isLoading = false,
                        history = refreshed?.second ?: current.history,
                        workingPreview = refreshed?.first ?: current.workingPreview,
                        restore = null,
                        errorMessage = error.message ?: "Project가 변경되어 복원을 취소했습니다.",
                    )
                    return@launch
                }
                val current = _historyUiState.value
                _historyUiState.value = if (restoreApplied) {
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
        val snapshot = workspace()
        if (!snapshot.canStartProjectOperation()) return
        val state = _createUiState.value
        val preview = state.preview ?: return
        if (state.isCommitting || !preview.hasChanges || message.isBlank()) return
        val project = snapshot.project ?: return
        val context = snapshot.context
        val request = beginRequest()
        _createUiState.value = state.copy(isCommitting = true, errorMessage = null)
        scope.launch {
            try {
                reconciliationMutex.withLock {
                    if (!isCurrentRequest(request, context)) return@withLock
                    check(!workspace().pendingPropertyDefinitionSync) {
                        "기본 속성 설정 저장을 완료한 뒤 커밋해 주세요."
                    }
                    saveCoordinator.flushAll()
                    if (!isCurrentRequest(request, context)) return@withLock
                    service.commit(project, message)
                }
                if (!isCurrentRequest(request, context)) return@launch
                _createUiState.value = CommitCreateUiState(isOpen = true, isCompleted = true)
                _historyUiState.value = CommitHistoryUiState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isCurrentRequest(request, context)) return@launch
                _createUiState.value = _createUiState.value.copy(
                    isCommitting = false,
                    errorMessage = error.message ?: "커밋을 만들지 못했습니다.",
                )
            }
        }
    }

    fun clear(preserveBaseline: Boolean) {
        cancelRequests()
        if (!preserveBaseline) {
            baselineJob?.cancel()
            baselineJob = null
            _baselineUiState.value = ProjectBaselineUiState.Idle
        }
        _createUiState.value = CommitCreateUiState()
        _historyUiState.value = CommitHistoryUiState()
    }

    private fun beginRequest(): Long {
        readJob?.cancel()
        readJob = null
        return ++requestGeneration
    }

    private fun cancelRequests() {
        requestGeneration++
        readJob?.cancel()
        readJob = null
    }

    private fun isCurrentRequest(request: Long, context: WorkspaceReadContext): Boolean =
        request == requestGeneration && isCurrentWorkspace(context)

    private fun CommitWorkspaceContext.canStartProjectOperation(): Boolean =
        !inputBlocked && !pendingPropertyDefinitionSync && kind == WorkspaceKind.PROJECT

    private fun CommitHistoryUiState.canRequestRestore(): Boolean =
        isOpen && !isLoading && restore?.isRestoring != true && messageEdit == null
}

internal data class CommitWorkspaceContext(
    val project: PlatformFile?,
    val kind: WorkspaceKind,
    val context: WorkspaceReadContext,
    val inputBlocked: Boolean,
    val pendingPropertyDefinitionSync: Boolean,
)

internal data class CommitRestoreSelection(
    val fileKey: FileKey?,
    val folderKey: FolderKey?,
)
