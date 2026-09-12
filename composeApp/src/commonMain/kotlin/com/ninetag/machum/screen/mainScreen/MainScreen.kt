package com.ninetag.machum.screen.mainScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import com.ninetag.machum.theme.WorkspaceDrawerMotionTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.CommitFileSide
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
import com.ninetag.machum.entity.effectiveAutoTags
import com.ninetag.machum.entity.normalizeTag
import com.ninetag.machum.external.DocumentInfoPreferences
import org.koin.compose.koinInject
import kotlinx.coroutines.CancellationException
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.screen.commitScreen.CommitWorkspaceScreen
import com.ninetag.machum.screen.commitScreen.CommitWorkspaceTab
import com.ninetag.machum.screen.commitScreen.CommitRestoreActionAvailability
import com.ninetag.machum.screen.commitScreen.CommitRestoreConfirmationDialog
import com.ninetag.machum.screen.mainScreen.leftSideMenu.ProjectNavigationDrawer
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import com.ninetag.machum.screen.common.WorkspaceBackHandler
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen(viewModel: MainViewModel = koinViewModel()) {

    val documentInfoPreferences = koinInject<DocumentInfoPreferences>()
    val documentInfoExpanded by documentInfoPreferences.expanded.collectAsState(initial = false)
    var documentInfoPreferenceError by remember { mutableStateOf<String?>(null) }
    val documentConflicts by viewModel.documentConflicts.collectAsState()
    val generalSourceState by viewModel.generalSourceState.collectAsState()
    val hierarchy by viewModel.hierarchyState.collectAsState()
    val folderList = hierarchy.folderList
    val currentFolder = hierarchy.currentFolder
    val fileList = hierarchy.fileList
    val currentIndex = hierarchy.currentIndex
    val plotFileEntries = hierarchy.plotFileEntries
    val fileLoadStates by viewModel.fileLoadStates.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val projectConfig by viewModel.projectConfig.collectAsState()
    val pendingFolderDeletion by viewModel.pendingFolderDeletion.collectAsState()
    val pendingFileTrash by viewModel.pendingFileTrash.collectAsState()
    val commitCreateUiState by viewModel.commitCreateUiState.collectAsState()
    val commitHistoryUiState by viewModel.commitHistoryUiState.collectAsState()
    val projectBaselineUiState by viewModel.projectBaselineUiState.collectAsState()
    val workspaceSaveError by viewModel.workspaceSaveError.collectAsState()
    val workspaceTransitionError by viewModel.workspaceTransitionError.collectAsState()
    val workspaceSelectionPending by viewModel.workspaceSelectionPending.collectAsState()
    val canRetryCreation by viewModel.canRetryCreation.collectAsState()
    val creationInProgress by viewModel.creationInProgress.collectAsState()
    val workspaceError = workspaceSaveError ?: workspaceTransitionError
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(workspaceSelectionPending) {
        if (workspaceSelectionPending) focusManager.clearFocus(force = true)
    }
    val currentWorkspaceKind = bookmarks.workspaceKind
    val isManagedProject = currentWorkspaceKind == WorkspaceKind.PROJECT
    val currentProjectLocation = bookmarks.projectData?.toString()
    var conflictReloadTarget by remember(currentProjectLocation, currentWorkspaceKind) { mutableStateOf<FileKey?>(null) }
    val baselineNeedsPreparation = isManagedProject && currentProjectLocation != null && (
        projectBaselineUiState is ProjectBaselineUiState.Idle ||
            projectBaselineUiState.projectLocation != currentProjectLocation
        )

    LaunchedEffect(currentProjectLocation, bookmarks.workspaceKind, baselineNeedsPreparation) {
        if (baselineNeedsPreparation) {
            focusManager.clearFocus(force = true)
            viewModel.ensureInitialProjectBaseline()
        }
    }

    val baselineReady = !isManagedProject || (
        projectBaselineUiState is ProjectBaselineUiState.Ready &&
            projectBaselineUiState.projectLocation == currentProjectLocation
        )
    if (!baselineReady) {
        ProjectBaselineGate(
            state = projectBaselineUiState,
            projectLocation = currentProjectLocation,
            onRetry = viewModel::ensureInitialProjectBaseline,
            onContinueWithoutBaseline = viewModel::skipInitialProjectBaseline,
        )
        return
    }

    val pagerState = rememberDocumentPagerState(
        identity = listOf(currentProjectLocation, currentWorkspaceKind, currentFolder?.key),
        pageKeys = fileList.map { it.key },
        selectedPage = currentIndex,
        onPageSettled = viewModel::selectFile,
    )

    // 앱/창 포커스 상태 → 외부 변경 감지 활성/비활성.
    // 포커스 복귀 시 즉시 재검사(Phase 1) + 포커스 유지 중 주기 폴링(Phase 2). 포커스 상실 시 폴링 중단.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, viewModel) {
        try {
            snapshotFlow { windowInfo.isWindowFocused }
                .collect { focused -> viewModel.setActive(focused) }
        } finally {
            // 같은 Window에서 선택 화면으로 나가도 ViewModel은 유지될 수 있다.
            viewModel.setActive(false)
        }
    }

    // 파일 목록과 선택은 같은 snapshot이다. 이동 중인 pager의 숫자 index로 선택을 보정하지 않는다.
    val currentFile = hierarchy.currentFile
    val isProjectRoot = currentFolder?.key?.let { it == FolderKey.Base } != false
    val currentFolderConfig = if (!isManagedProject) FolderConfig(type = FolderType.GENERAL)
        else currentFolder?.key?.relativePath
        ?.let { projectConfig?.folders?.get(it) }
        ?: if (isProjectRoot) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) viewModel.refreshProjectList()
    }

    val commitWorkspaceOpen = commitCreateUiState.isOpen || commitHistoryUiState.isOpen
    val onCommitBack: () -> Unit = {
        if (!commitCreateUiState.isCommitting && commitHistoryUiState.restore?.isRestoring != true) {
            if (commitHistoryUiState.isOpen) viewModel.navigateBackFromCommitHistory()
            else if (commitCreateUiState.diff != null) viewModel.closeCommitDiff()
            else viewModel.dismissCommitWorkspace()
        }
    }
    LaunchedEffect(commitWorkspaceOpen) {
        if (commitWorkspaceOpen) {
            focusManager.clearFocus(force = true)
            if (drawerState.isOpen) drawerState.close()
        }
    }

    val requestFileCreation: (FolderKey, PlotStage?) -> Unit = { folderKey, stage ->
        if (!creationInProgress) currentProjectLocation?.let { location ->
            viewModel.createFile(CreateFileRequest(location, bookmarks.workspaceKind, folderKey, stage), "무제", stage)
            scope.launch { drawerState.close() }
        }
    }


    // Root barrier is registered first; later drawer/dialog/commit handlers retain priority.
    WorkspaceBackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            else -> focusManager.clearFocus(force = true)
        }
    }
    WorkspaceBackHandler(commitWorkspaceOpen && commitHistoryUiState.restore == null) {
        onCommitBack()
    }

    WorkspaceDrawerMotionTheme { contentMotionScheme ->
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !commitWorkspaceOpen,
            drawerContent = {
                MaterialTheme(motionScheme = contentMotionScheme) {
                    val projectList by viewModel.projectList.collectAsState()
                    val generalFolderList by viewModel.generalFolderList.collectAsState()
                    ProjectNavigationDrawer(
                        projects = projectList,
                        generalFolders = generalFolderList,
                        currentProject = bookmarks.projectData,
                        isManagedProject = isManagedProject,
                        creationInProgress = creationInProgress,
                        generalSourceState = generalSourceState,
                        onCreateGeneralSourceGroup = viewModel::createGeneralSourceGroup,
                        onCreateGeneralSourceFile = viewModel::createGeneralSourceFile,
                        onPlanGeneralSourceRename = viewModel::planGeneralSourceRename,
                        onPlanGeneralSourceDelete = viewModel::planGeneralSourceDelete,
                        onPlanGeneralSourceAssign = viewModel::planGeneralSourceAssign,
                        onApplyGeneralSourcePlan = viewModel::applyGeneralSourcePlan,

                        folders = folderList,
                        folderConfigs = projectConfig?.folders.orEmpty(),
                        currentFolder = currentFolder,
                        folderContents = hierarchy.folderContents,
                        currentFile = currentFile,
                        onProjectSelected = { project ->
                            focusManager.clearFocus(force = true)
                            scope.launch { drawerState.close() }
                            viewModel.selectProject(project)
                        },
                        onProjectWorkspaceSelected = {
                            focusManager.clearFocus(force = true)
                            scope.launch { drawerState.close() }
                            viewModel.returnToLastProject()
                        },
                        onWorkspaceSelection = {
                            focusManager.clearFocus(force = true)
                            scope.launch { drawerState.close() }
                            viewModel.openWorkspaceSelection()
                        },
                        onFolderSelected = { folderKey ->
                            viewModel.selectFolder(folderKey)
                        },
                        onFileSelected = { file ->
                            viewModel.selectFile(file.key)
                            scope.launch { drawerState.close() }
                        },
                        onCreateFile = { folderKey ->
                            requestFileCreation(folderKey, null)
                        },
                        onCreatePlotFile = { folderKey, stage ->
                            requestFileCreation(folderKey, stage)
                        },
                        onSaveDefaultOrder = viewModel::saveDefaultOrder,
                        onSavePlotOrder = viewModel::savePlotOrder,
                        onCreateDirectory = viewModel::createDirectory,
                        onUpdateDirectory = viewModel::updateDirectoryAndAwait,
                        onUpdateFolderTags = viewModel::updateFolderAutoTagsAndAwait,
                        onUpdateFolderType = viewModel::updateFolderTypeAndAwait,
                        pendingFolderDeletion = pendingFolderDeletion,
                        onDeleteDirectoryRequested = viewModel::requestDeleteDirectory,
                        onDeleteDirectoryDismissed = viewModel::dismissDeleteDirectory,
                        onDeleteDirectoryConfirmed = viewModel::confirmDeleteDirectory,
                        onFileTrashRequested = viewModel::requestMoveFileToTrash,
                        onClose = { scope.launch { drawerState.close() } },
                    )
                }
            },
        ) {
            MaterialTheme(motionScheme = contentMotionScheme) {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = if (commitWorkspaceOpen || workspaceSelectionPending) {
                            Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false }
                        } else {
                            Modifier
                        },
                        topBar = {
                            EditorTopBar(
                                projectFile = currentFile,
                                folderName = currentFolder
                                    ?.takeUnless { isProjectRoot }
                                    ?.key
                                    ?.relativePath,
                                onNavigateBack = if (isProjectRoot) null else viewModel::navigateToProjectRoot,
                                onMenuClick = { scope.launch { drawerState.open() } },
                                onCommitClick = if (isManagedProject) viewModel::openCommitDialog else null,
                                documentInfoExpanded = documentInfoExpanded,
                                onDocumentInfoToggle = {
                                    focusManager.clearFocus()
                                    scope.launch {
                                        try { documentInfoPreferences.setExpanded(!documentInfoExpanded); documentInfoPreferenceError = null }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { documentInfoPreferenceError = "문서 정보 표시 설정을 저장하지 못했습니다." }
                                    }
                                },
                                onRenameFile = viewModel::renameFile,
                            )
                        },
                    ) { paddingValues ->
                        BoxWithConstraints(modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        ) {
                            val propertyHeightLimit = (maxHeight * 0.45f).coerceAtMost(260.dp)
                            Column(Modifier.fillMaxSize()) {
                                val managedTags = if (isManagedProject) buildMap {
                                    bookmarks.projectData?.name?.let { put(normalizeTag(it), "프로젝트") }
                                    projectConfig?.effectiveAutoTags(currentFolder?.key?.relativePath.orEmpty())?.forEach { put(it, "폴더 자동 태그") }
                                } else emptyMap()
                                DocumentInformationSection(
                                    workspaceIdentity = "$currentWorkspaceKind:$currentProjectLocation",
                                    file = currentFile,
                                    note = (currentFile?.key?.let { fileLoadStates[it] } as? FileLoadUiState.Loaded)?.noteFile,
                                    expanded = documentInfoExpanded,
                                    maxExpandedHeight = propertyHeightLimit,
                                    managedTags = managedTags,
                                    sourceIsManaged = !isManagedProject && generalSourceState?.enabled == true,
                                    protectedKeys = if (isManagedProject) emptySet() else setOf("source", "tags"),
                                    onSave = { fileKey, expected, updated ->
                                        if (viewModel.bookmarks.value.projectData?.toString() != currentProjectLocation ||
                                            viewModel.bookmarks.value.workspaceKind != currentWorkspaceKind) "작업 공간이 변경되었습니다."
                                        else viewModel.saveDocumentProperties(fileKey, expected, updated)
                                    },
                                )
                                documentInfoPreferenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Box(Modifier.weight(1f)) {
                                    if (fileList.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = "이 폴더에는 Markdown 파일이 없습니다.",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = "새 파일을 만들어 글쓰기를 시작하세요.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(Modifier.height(12.dp))
                                                key(currentProjectLocation, bookmarks.workspaceKind, currentFolder?.key, currentFolderConfig.isPlot) {
                                                    CreateFileAction(
                                                        isPlot = currentFolderConfig.isPlot,
                                                        enabled = !creationInProgress,
                                                        onCreate = { stage -> currentFolder?.key?.let { requestFileCreation(it, stage) } },
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        HorizontalPager(
                                            state = pagerState,
                                            modifier = Modifier.fillMaxSize(),
                                            userScrollEnabled = !imeVisible,
                                            key = { page ->
                                                fileList.getOrNull(page)
                                                    ?.let { viewModel.editorSessionKey(it.key) }
                                                    ?: "empty-$page"
                                            },
                                        ) { page ->
                                            val projectFile = fileList.getOrNull(page) ?: return@HorizontalPager
                                            EditorPage(
                                                projectFile = projectFile,
                                                documentKey = viewModel.editorSessionKey(projectFile.key),
                                                loadState = fileLoadStates[projectFile.key],
                                                onLoad = viewModel::loadPage,
                                                onRetry = viewModel::retryPage,
                                                onBodyChange = { body -> viewModel.updateBody(projectFile.key, body) },
                                            )
                                        }
                                    }

                                }
                            }

                        }
                    }

                    if (workspaceSelectionPending || creationInProgress) {
                        Box(
                            Modifier.matchParentSize().zIndex(3f)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    if (commitWorkspaceOpen) {
                        // 전용 화면에서 clickable이 없는 빈 영역도 뒤 editor로 입력을 통과시키지 않는다.
                        Box(
                            Modifier
                                .matchParentSize()
                                .zIndex(1f)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                                .changes
                                                .forEach { it.consume() }
                                        }
                                    }
                                },
                        )
                        CommitWorkspaceScreen(
                            projectName = bookmarks.projectData?.name.orEmpty(),
                            selectedTab = if (commitHistoryUiState.isOpen) CommitWorkspaceTab.History else CommitWorkspaceTab.Changes,
                            createState = commitCreateUiState,
                            historyState = commitHistoryUiState,
                            message = commitCreateUiState.message,
                            onMessageChange = viewModel::updateCommitMessage,
                            onTabSelected = { tab ->
                                if (tab == CommitWorkspaceTab.History) viewModel.openCommitHistory()
                                else viewModel.openCommitDialog()
                            },
                            onCommit = viewModel::createCommit,
                            onCreateDiffRequest = viewModel::openCommitDiff,
                            onCreateRetry = viewModel::openCommitDialog,
                            fileContentRestoreAvailability = { change, _ ->
                                commitRestoreAvailability(
                                    state = commitHistoryUiState,
                                    change = change,
                                    requiresCurrentFile = true,
                                )
                            },
                            fileRestoreAvailability = { change, _ ->
                                commitRestoreAvailability(
                                    state = commitHistoryUiState,
                                    change = change,
                                    requiresCurrentFile = false,
                                )
                            },
                            onBack = onCommitBack,
                            onHistoryRetry = viewModel::retryCommitHistory,
                            onCommitSelected = viewModel::selectCommitHistoryEntry,
                            onHistoryDiffRequest = viewModel::openCommitHistoryDiff,
                            onProjectRestoreRequest = viewModel::requestProjectRestore,
                            onHeadRevertRequest = viewModel::requestHeadRevert,
                            onFileContentRestoreRequest = viewModel::requestFileContentRestore,
                            onFileRestoreRequest = viewModel::requestFileRestore,
                            modifier = Modifier
                                .matchParentSize()
                                .zIndex(2f),
                        )
                    }
                }
            }
        }
    }

    commitHistoryUiState.restore?.let { restore ->
        val copy = restoreCopy(restore.target)
        CommitRestoreConfirmationDialog(
            title = copy.title,
            body = copy.body,
            confirmLabel = copy.confirmLabel,
            busyLabel = copy.busyLabel,
            isRestoring = restore.isRestoring,
            errorMessage = restore.errorMessage,
            onConfirm = viewModel::confirmCommitRestore,
            onDismissRequest = viewModel::dismissCommitRestore,
        )
    }

    pendingFileTrash?.let { state ->
        FileTrashDialog(
            state = state,
            onConfirm = viewModel::confirmMoveFileToTrash,
            onDismissRequest = viewModel::dismissFileTrash,
        )
    }

    if (workspaceError != null) {
        PolicyDialog(
            onDismissRequest = viewModel::dismissWorkspaceTransitionError,
            title = "작업을 완료하지 못했습니다",
            dismissOnClickOutside = true,
            confirmButton = {
                Button(
                    onClick = viewModel::dismissWorkspaceTransitionError,
                    modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
                ) {
                    Text("닫기")
                }
            },
            dismissButton = {
                if (canRetryCreation) TextButton(onClick = viewModel::retryCreation, enabled = !creationInProgress) {
                    Text("남은 기록 재시도")
                }
            },
        ) {
            Text(workspaceError)
            documentConflicts.forEach { (fileKey, _) ->
                TextButton(onClick = { conflictReloadTarget = fileKey }) {
                    Text("${fileKey.relativePath} · 디스크 문서 다시 읽기")
                }
            }
        }
    }
    conflictReloadTarget?.let { fileKey ->
        PolicyDialog(
            onDismissRequest = { conflictReloadTarget = null },
            title = "디스크 문서 다시 읽기",
            confirmButton = { Button(onClick = {
                viewModel.reloadConflictedDocument(fileKey)
                conflictReloadTarget = null
                viewModel.dismissWorkspaceTransitionError()
            }) { Text("다시 읽기") } },
            dismissButton = { TextButton(onClick = { conflictReloadTarget = null }) { Text("취소") } },
        ) { Text("${fileKey.relativePath}의 저장하지 않은 본문 초안을 버리고 디스크의 최신 내용을 읽습니다. 디스크 파일은 변경하지 않습니다.") }
    }
}

@Composable
private fun ProjectBaselineGate(
    state: ProjectBaselineUiState,
    projectLocation: String?,
    onRetry: () -> Unit,
    onContinueWithoutBaseline: () -> Unit,
) {
    val error = (state as? ProjectBaselineUiState.Error)
        ?.takeIf { it.projectLocation == projectLocation }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("첫 편집 전 복원 기준점을 준비하는 중입니다.")
            } else {
                Text(
                    text = "초기 기준점을 만들지 못했습니다.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text("다시 시도") }
                TextButton(onClick = onContinueWithoutBaseline) {
                    Text("기준점 없이 계속")
                }
            }
        }
    }
}

private fun commitRestoreAvailability(
    state: CommitHistoryUiState,
    change: CommitChange,
    requiresCurrentFile: Boolean,
): CommitRestoreActionAvailability = when {
    state.workingPreview == null -> CommitRestoreActionAvailability(
        enabled = false,
        disabledReason = "현재 변경 상태를 확인한 뒤 복원할 수 있습니다.",
    )
    requiresCurrentFile && change.fileId !in state.workingPreview.currentFileIds ->
        CommitRestoreActionAvailability(
            enabled = false,
            disabledReason = "현재 파일이 없습니다. 단일 파일 전체 복원을 사용하세요.",
        )
    else -> CommitRestoreActionAvailability.Enabled
}

private data class RestoreDialogCopy(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val busyLabel: String,
)

private fun restoreCopy(target: CommitRestoreTarget): RestoreDialogCopy = when (target) {
    is CommitRestoreTarget.Project -> RestoreDialogCopy(
        title = "프로젝트 전체 복원",
        body = "‘${target.entry.commit.message}’ 커밋 시점의 모든 추적 Markdown 파일로 되돌립니다. " +
            "현재 미커밋 변경은 폐기됩니다. 파일의 내용·이름·경로·존재 여부가 바뀌며, 커밋 이력과 프로젝트 설정은 유지됩니다.",
        confirmLabel = "전체 복원",
        busyLabel = "프로젝트를 복원하는 중…",
    )
    is CommitRestoreTarget.HeadSnapshot -> RestoreDialogCopy(
        title = "현재 커밋 시점으로 복원",
        body = "현재의 미커밋 변경을 모두 폐기하고 ‘${target.entry.commit.message}’ 커밋 시점의 " +
            "추적 Markdown 파일로 복원합니다. 파일의 내용·이름·경로·존재 여부가 바뀌지만 " +
            "HEAD와 커밋 이력, 프로젝트 설정은 유지됩니다.",
        confirmLabel = "현재 변경 폐기",
        busyLabel = "현재 커밋 시점으로 복원하는 중…",
    )
    is CommitRestoreTarget.HeadChanges -> RestoreDialogCopy(
        title = "최근 커밋 이전 상태로 되돌리기",
        body = "‘${target.entry.commit.message}’ 커밋 바로 전의 Project 상태를 작업 파일에 적용합니다. " +
            "현재 미커밋 변경은 폐기됩니다. 현재 HEAD와 기존 커밋 이력은 유지되며, 결과는 새 미커밋 변경으로 표시됩니다.",
        confirmLabel = "변경 되돌리기",
        busyLabel = "최근 커밋의 변경을 되돌리는 중…",
    )
    is CommitRestoreTarget.FileContent -> RestoreDialogCopy(
        title = "파일 내용 복원",
        body = "${target.side.label}의 본문과 일반 frontmatter를 현재 파일에 적용합니다. " +
            "이 파일의 미커밋 내용 변경은 폐기됩니다. 현재 파일명·경로·순번과 id·plot 정보 및 다른 파일의 변경은 유지됩니다.",
        confirmLabel = "내용 복원",
        busyLabel = "파일 내용을 복원하는 중…",
    )
    is CommitRestoreTarget.File -> {
        val path = target.change.path(target.side)
        RestoreDialogCopy(
            title = "단일 파일 전체 복원",
            body = "이 파일의 미커밋 변경은 폐기하며 다른 파일은 유지합니다. " + if (path == null) {
                "선택한 ${target.side.label} 상태에는 파일이 없습니다. 현재 파일 한 개를 삭제 상태로 되돌립니다."
            } else {
                "파일 한 개를 ${target.side.label} 상태와 동일하게 복원합니다. 내용과 함께 이름·경로·존재 여부가 바뀔 수 있습니다: $path"
            },
            confirmLabel = "파일 전체 복원",
            busyLabel = "파일을 복원하는 중…",
        )
    }
}

/** A new folder/list must not inherit an old drag offset or an in-flight page animation. */
@Composable
internal fun <T> rememberDocumentPagerState(
    identity: Any?,
    pageKeys: List<T>,
    selectedPage: Int,
    onPageSettled: (T) -> Unit,
): PagerState = key(identity, pageKeys) {
    val targetPage = selectedPage.coerceIn(0, (pageKeys.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = targetPage, pageCount = { pageKeys.size })
    val latestOnPageSettled by rememberUpdatedState(onPageSettled)
    val pendingSelectionReports = remember(pagerState) { mutableListOf<Int>() }
    var lastReportedPage by remember(pagerState) { mutableStateOf(targetPage) }
    LaunchedEffect(pagerState, targetPage) {
        // A delayed response to our own settled-page report must not cancel a newer drag.
        // Only an external selection settles both coordinates, including a residual partial offset.
        val acknowledgedReport = pendingSelectionReports.indexOf(targetPage)
        if (acknowledgedReport >= 0) {
            // The navigation gate can supersede older reports without publishing their response.
            repeat(acknowledgedReport + 1) { pendingSelectionReports.removeAt(0) }
        } else {
            pendingSelectionReports.clear()
            if (pageKeys.isNotEmpty()) pagerState.scrollToPage(targetPage)
            lastReportedPage = targetPage
        }
        snapshotFlow { pagerState.settledPage.takeUnless { pagerState.isScrollInProgress } }
            .filterNotNull()
            .collect { page ->
                if (page != lastReportedPage) {
                    lastReportedPage = page
                    pageKeys.getOrNull(page)?.let {
                        if (page == targetPage) {
                            // Returning to the already published page produces no new state value.
                            // Clear superseded reports now; there will be no acknowledgment effect.
                            pendingSelectionReports.clear()
                        } else {
                            pendingSelectionReports.remove(page)
                            pendingSelectionReports += page
                        }
                        latestOnPageSettled(it)
                    }
                }
            }
    }
    pagerState
}

private val CommitFileSide.label: String
    get() = when (this) {
        CommitFileSide.BEFORE -> "변경 전"
        CommitFileSide.AFTER -> "변경 후"
    }

private fun CommitChange.path(side: CommitFileSide): String? = when (side) {
    CommitFileSide.BEFORE -> oldPath
    CommitFileSide.AFTER -> newPath
}
