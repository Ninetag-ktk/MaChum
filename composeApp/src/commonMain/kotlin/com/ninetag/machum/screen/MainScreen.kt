package com.ninetag.machum.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.markdownName
import com.ninetag.machum.screen.mainComposition.CreateProjectFileDialog
import com.ninetag.machum.screen.mainComposition.EditorNavigationMenu
import com.ninetag.machum.screen.mainComposition.EditorPage
import com.ninetag.machum.screen.mainComposition.EditorTopBar
import com.ninetag.machum.screen.mainComposition.MainViewModel
import com.ninetag.machum.screen.mainComposition.PlotOrderEditorDialog
import com.ninetag.machum.screen.mainComposition.ProjectNavigationDrawer
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen() {
    val viewModel: MainViewModel = koinViewModel()

    val folderList by viewModel.folderList.collectAsState()
    val currentFolder by viewModel.currentFolder.collectAsState()
    val fileList by viewModel.fileList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val projectList by viewModel.projectList.collectAsState()
    val projectConfig by viewModel.projectConfig.collectAsState()
    val plotFileEntries by viewModel.plotFileEntries.collectAsState()
    var navigationExpanded by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showPlotOrderEditor by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { fileList.size.coerceAtLeast(1) }
    )

    LaunchedEffect(pagerState.isScrollInProgress, fileList.size) {
        if (fileList.isNotEmpty() && !pagerState.isScrollInProgress) {
            viewModel.onPageChanged(pagerState.currentPage)
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

    val currentFile = fileList.getOrNull(pagerState.currentPage)
        ?: fileList.getOrNull(currentIndex)
    val emptyTitle = currentFolder?.key?.relativePath
        ?.substringAfterLast('/')
        ?.ifEmpty { "프로젝트 루트" }
        ?: "빈 폴더"
    val isProjectRoot = currentFolder?.key?.let { it == FolderKey.Base } != false
    val currentFolderConfig = currentFolder?.key?.relativePath
        ?.let { projectConfig?.folders?.get(it) }
        ?: FolderConfig()

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) viewModel.refreshProjectList()
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
                onVaultChange = {
                    scope.launch { drawerState.close() }
                    viewModel.resetFileManager()
                },
                onProjectSelected = { project ->
                    viewModel.selectProject(project)
                    scope.launch { drawerState.close() }
                },
                onFolderSelected = { folderKey ->
                    viewModel.selectFolder(folderKey)
                    scope.launch { drawerState.close() }
                },
                onCreateDirectory = viewModel::createDirectory,
                onUpdateDirectoryConfig = viewModel::updateDirectoryConfig,
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            topBar = {
                EditorTopBar(
                    fileName = currentFile?.platformFile?.markdownName(),
                    emptyTitle = emptyTitle,
                    folderName = currentFolder
                        ?.takeUnless { isProjectRoot }
                        ?.key
                        ?.relativePath,
                    onNavigateBack = if (isProjectRoot) null else viewModel::navigateToProjectRoot,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onCommitClick = { /*TODO*/ },
                    onFileListClick = { navigationExpanded = true },
                    onRenameFile = { newName ->
                        currentFile?.let { viewModel.onRenameFile(it, newName) }
                    },
                    navigationMenuContent = {
                        EditorNavigationMenu(
                            expanded = navigationExpanded,
                            files = fileList,
                            currentFile = currentFile,
                            onDismissRequest = { navigationExpanded = false },
                            onFileSelected = viewModel::selectFile,
                            onCreateFile = { showCreateFileDialog = true },
                            onEditPlotOrder = if (currentFolderConfig.isPlot) {
                                { showPlotOrderEditor = true }
                            } else null,
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
                    Text("이 폴더에는 Markdown 파일이 없습니다.\n상단 메뉴에서 새 파일을 만들 수 있습니다.")
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.padding(it)
                ) { page ->
                    EditorPage(projectFile = fileList[page])
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
            onDismissRequest = { showCreateFileDialog = false },
            onCreate = { title, stage ->
                if (currentFolderConfig.isPlot) {
                    stage?.let { viewModel.createPlotFile(it, title) }
                } else {
                    viewModel.createFileInCurrentFolder(title)
                }
            },
        )
    }

    if (showPlotOrderEditor) {
        PlotOrderEditorDialog(
            entries = plotFileEntries,
            onDismissRequest = { showPlotOrderEditor = false },
            onSave = viewModel::savePlotOrder,
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
}
