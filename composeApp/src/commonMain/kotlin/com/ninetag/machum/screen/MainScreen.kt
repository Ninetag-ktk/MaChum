package com.ninetag.machum.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.screen.mainComposition.CreateProjectFileDialog
import com.ninetag.machum.screen.mainComposition.CommitDialog
import com.ninetag.machum.screen.mainComposition.EditorNavigationMenu
import com.ninetag.machum.screen.mainComposition.EditorPage
import com.ninetag.machum.screen.mainComposition.EditorTopBar
import com.ninetag.machum.screen.mainComposition.HierarchyFolderContent
import com.ninetag.machum.screen.mainComposition.MainViewModel
import com.ninetag.machum.screen.mainComposition.ProjectNavigationDrawer
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen() {
    val viewModel: MainViewModel = koinViewModel()

    val folderList by viewModel.folderList.collectAsState()
    val currentFolder by viewModel.currentFolder.collectAsState()
    val fileList by viewModel.fileList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val fileLoadStates by viewModel.fileLoadStates.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val projectList by viewModel.projectList.collectAsState()
    val projectConfig by viewModel.projectConfig.collectAsState()
    val plotFileEntries by viewModel.plotFileEntries.collectAsState()
    val hierarchyFolderContents by viewModel.hierarchyFolderContents.collectAsState()
    val pendingFolderDeletion by viewModel.pendingFolderDeletion.collectAsState()
    val projectRenameState by viewModel.projectRenameState.collectAsState()
    val commitUiState by viewModel.commitUiState.collectAsState()
    val workspaceSaveError by viewModel.workspaceSaveError.collectAsState()
    val workspaceTransitionError by viewModel.workspaceTransitionError.collectAsState()
    var navigationExpanded by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var pendingCreateFileTarget by remember { mutableStateOf<CreateFileTarget?>(null) }
    var createFileInitialPlotStage by remember { mutableStateOf<PlotStage?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { fileList.size }
    )

    val latestFileList by rememberUpdatedState(fileList)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            // 최초 page는 목록·bookmark 복원이 끝나기 전의 임시 값일 수 있다.
            .drop(1)
            .collect { page ->
                if (latestFileList.getOrNull(page) != null) {
                    viewModel.onPageChanged(page)
                }
            }
    }
    LaunchedEffect(currentIndex, fileList.size) {
        if (fileList.isNotEmpty() && pagerState.currentPage != currentIndex) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }

    // 앱/창 포커스 상태 → 외부 변경 감지 활성/비활성.
    // 포커스 복귀 시 즉시 재검사(Phase 1) + 포커스 유지 중 주기 폴링(Phase 2). 포커스 상실 시 폴링 중단.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }
            .collect { focused -> viewModel.setActive(focused) }
    }

    // ViewModel 선택이 authority다. rename 재정렬 중 pager의 이전 숫자 index를 먼저 읽으면
    // 잠시 다른 파일을 선택한 것처럼 보일 수 있으므로 pager는 복구용 fallback으로만 사용한다.
    val currentFile = fileList.getOrNull(currentIndex)
        ?: fileList.getOrNull(pagerState.currentPage)
    val isProjectRoot = currentFolder?.key?.let { it == FolderKey.Base } != false
    val currentFolderConfig = currentFolder?.key?.relativePath
        ?.let { projectConfig?.folders?.get(it) }
        ?: if (isProjectRoot) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()
    // 현재 pager 목록은 이미 화면의 authority이므로 drawer 전체 snapshot의 같은 폴더를
    // 최신 값으로 덮는다. 초기 비동기 수집 순서와 무관하게 선택 폴더 항목이 비어 보이지 않는다.
    val drawerFolderContents = remember(
        hierarchyFolderContents,
        currentFolder?.key,
        fileList,
        plotFileEntries,
    ) {
        currentFolder?.key?.let { folderKey ->
            hierarchyFolderContents + (
                folderKey to HierarchyFolderContent(
                    files = fileList,
                    plotEntries = plotFileEntries,
                )
            )
        } ?: hierarchyFolderContents
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) viewModel.refreshProjectList()
    }

    LaunchedEffect(pendingCreateFileTarget, currentFolder?.key) {
        val target = pendingCreateFileTarget ?: return@LaunchedEffect
        if (currentFolder?.key == target.folderKey) {
            drawerState.close()
            createFileInitialPlotStage = target.plotStage
            showCreateFileDialog = true
            pendingCreateFileTarget = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ProjectNavigationDrawer(
                vault = bookmarks.vaultData,
                projects = projectList,
                currentProject = bookmarks.projectData,
                folders = folderList,
                folderConfigs = projectConfig?.folders.orEmpty(),
                currentFolder = currentFolder,
                folderContents = drawerFolderContents,
                currentFile = currentFile,
                onVaultChange = {
                    scope.launch { drawerState.close() }
                    viewModel.resetFileManager()
                },
                onProjectSelected = { project ->
                    viewModel.selectProject(project)
                    scope.launch { drawerState.close() }
                },
                onRenameProject = viewModel::renameCurrentProject,
                projectRenameState = projectRenameState,
                onProjectRenameResultConsumed = viewModel::consumeProjectRenameResult,
                onFolderSelected = { folderKey ->
                    viewModel.selectFolder(folderKey)
                },
                onFileSelected = { file ->
                    viewModel.selectFile(file.key)
                    scope.launch { drawerState.close() }
                },
                onCreateFile = { folderKey ->
                    pendingCreateFileTarget = CreateFileTarget(folderKey)
                    if (currentFolder?.key != folderKey) {
                        viewModel.selectFolder(folderKey)
                    }
                },
                onCreatePlotFile = { folderKey, stage ->
                    pendingCreateFileTarget = CreateFileTarget(folderKey, stage)
                    if (currentFolder?.key != folderKey) {
                        viewModel.selectFolder(folderKey)
                    }
                },
                onSaveDefaultOrder = viewModel::saveDefaultOrder,
                onSavePlotOrder = viewModel::savePlotOrder,
                onCreateDirectory = viewModel::createDirectory,
                onUpdateDirectory = viewModel::updateDirectory,
                pendingFolderDeletion = pendingFolderDeletion,
                onDeleteDirectoryRequested = viewModel::requestDeleteDirectory,
                onDeleteDirectoryDismissed = viewModel::dismissDeleteDirectory,
                onDeleteDirectoryConfirmed = viewModel::confirmDeleteDirectory,
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            topBar = {
                EditorTopBar(
                    projectFile = currentFile,
                    folderName = currentFolder
                        ?.takeUnless { isProjectRoot }
                        ?.key
                        ?.relativePath,
                    onNavigateBack = if (isProjectRoot) null else viewModel::navigateToProjectRoot,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onCommitClick = viewModel::openCommitDialog,
                    onFileListClick = { navigationExpanded = true },
                    onRenameFile = viewModel::renameFile,
                    navigationMenuContent = {
                        EditorNavigationMenu(
                            expanded = navigationExpanded,
                            files = fileList,
                            currentFile = currentFile,
                            onDismissRequest = { navigationExpanded = false },
                            onFileSelected = viewModel::selectFile,
                            onCreateFile = if (currentFolderConfig.isPlot) {
                                null
                            } else {
                                { showCreateFileDialog = true }
                            },
                        )
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showResetConfirmation = true },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "앱 데이터 초기화",
                    )
                }
            },
        ) {
            if (fileList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(it),
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
                        TextButton(onClick = { showCreateFileDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("새 파일", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.padding(it),
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

    if (showCreateFileDialog) {
        CreateProjectFileDialog(
            folderConfig = currentFolderConfig,
            defaultStartNumber = if (isProjectRoot) 0 else 1,
            files = fileList,
            plotEntries = plotFileEntries,
            initialPlotStage = createFileInitialPlotStage,
            onDismissRequest = {
                showCreateFileDialog = false
                createFileInitialPlotStage = null
            },
            onCreate = { title, stage ->
                if (currentFolderConfig.isPlot) {
                    stage?.let { viewModel.createPlotFile(it, title) }
                } else {
                    viewModel.createFileInCurrentFolder(title)
                }
            },
        )
    }

    if (commitUiState.isOpen) {
        CommitDialog(
            state = commitUiState,
            onDismissRequest = viewModel::dismissCommitDialog,
            onCommit = viewModel::createCommit,
            onDiffRequest = viewModel::openCommitDiff,
            onDiffDismiss = viewModel::closeCommitDiff,
            onRestoreRequest = viewModel::requestCommitRestore,
            onRestoreConfirm = viewModel::confirmCommitRestore,
            onRestoreDismiss = viewModel::dismissCommitRestore,
        )
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("앱 데이터를 초기화할까요?") },
            text = {
                Text("저장된 Vault, Project, File 선택 정보만 초기화합니다. 실제 파일은 삭제되지 않습니다.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        viewModel.resetFileManager()
                    }
                ) {
                    Text("초기화")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("취소")
                }
            },
        )
    }

    val workspaceError = workspaceSaveError ?: workspaceTransitionError
    if (workspaceError != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissWorkspaceTransitionError,
            title = { Text("작업을 저장하지 못했습니다") },
            text = { Text(workspaceError) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWorkspaceTransitionError) {
                    Text("확인")
                }
            },
        )
    }
}

private data class CreateFileTarget(
    val folderKey: FolderKey,
    val plotStage: PlotStage? = null,
)
