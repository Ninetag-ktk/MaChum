package com.ninetag.machum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.mainScreen.MainScreen
import com.ninetag.machum.screen.projectScreen.ProjectSelectionScreen
import com.ninetag.machum.screen.projectScreen.ProjectIndexingScreen
import com.ninetag.machum.screen.vaultScreen.VaultSelectionScreen
import com.ninetag.machum.external.ProjectIndexState
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.screen.projectScreen.WorkspaceSetupDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.ninetag.machum.theme.AppTheme
import com.ninetag.machum.screen.mainScreen.MainViewModel
import org.koin.compose.viewmodel.koinViewModel

import org.koin.compose.koinInject

@Composable
@Preview
fun App(vaultContent: @Composable (() -> Unit) -> Unit = { VaultSelectionScreen(reset = it) }) {
    val fileManager = koinInject<FileManager>()
    val viewModel: MainViewModel = koinViewModel()
    val selectionVisible by viewModel.workspaceSelectionVisible.collectAsState()
    val vaultSelectionVisible by viewModel.vaultSelectionVisible.collectAsState()
    val bookmark by fileManager.bookmarks.collectAsState()
    val projectIndexState by fileManager.projectIndexState.collectAsState()
    val openRequest by fileManager.workspaceOpenRequest.collectAsState()
    val scope = rememberCoroutineScope()
    var initialized by remember(fileManager) { mutableStateOf(false) }
    var initializationError by remember(fileManager) { mutableStateOf<String?>(null) }
    var initializationAttempt by remember(fileManager) { mutableStateOf(0) }

    LaunchedEffect(fileManager, initializationAttempt) {
        initializationError = null
        try {
            fileManager.initialize()
            initialized = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            initializationError = "작업 공간을 불러오지 못했습니다. ${error.message.orEmpty()}".trim()
        }
    }

    AppTheme {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    !initialized -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) {
                            if (initializationError == null) {
                                CircularProgressIndicator()
                                Text("작업 공간을 불러오는 중입니다.")
                            } else {
                                Text(initializationError!!, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { initializationAttempt += 1 }) { Text("다시 시도") }
                            }
                        }
                    }
                    bookmark.vaultData == null || vaultSelectionVisible ->
                        vaultContent(viewModel::completeVaultSelection)
                    bookmark.projectData == null || selectionVisible -> {
                        val selectionError by viewModel.workspaceTransitionError.collectAsState()
                        val saveError by viewModel.workspaceSaveError.collectAsState()
                        ProjectSelectionScreen(
                            operationScope = scope,
                            onVaultChange = viewModel::openVaultSelection,
                            onOpened = viewModel::completeWorkspaceSelection,
                            runOperation = { action -> viewModel.runWorkspaceSelectionAction(action) },
                            externalError = saveError ?: selectionError,
                            onErrorDismiss = viewModel::dismissWorkspaceTransitionError,
                        )
                    }
                    bookmark.workspaceKind == WorkspaceKind.GENERAL -> {
                        MainScreen(viewModel)
                    }
                    projectIndexState.projectLocation == bookmark.projectData.toString() &&
                        projectIndexState is ProjectIndexState.Preparing -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    projectIndexState.projectLocation == bookmark.projectData.toString() &&
                        projectIndexState is ProjectIndexState.Indexing -> {
                        ProjectIndexingScreen(projectIndexState as ProjectIndexState.Indexing)
                    }
                    else -> {
                        MainScreen(viewModel)
                    }
                }
            }
        }
        openRequest?.takeIf { initialized && !vaultSelectionVisible && (selectionVisible || bookmark.projectData == null) }?.let { request ->
            var confirming by remember(request.directory) { mutableStateOf(false) }
            var confirmationError by remember(request.directory) { mutableStateOf<String?>(null) }
            WorkspaceSetupDialog(
                request = request.copy(busy = request.busy || confirming,
                    errorMessage = confirmationError ?: request.errorMessage),
                onDismiss = { if (!confirming) fileManager.dismissWorkspaceOpenRequest() },
                onConfirm = { kind -> scope.launch {
                    if (!confirming) {
                        confirming = true
                        confirmationError = null
                        try {
                            viewModel.confirmWorkspaceOpen(kind).onFailure {
                                confirmationError = it.message ?: "폴더를 열지 못했습니다."
                            }
                        } finally { confirming = false }
                    }
                } },
            )
        }
    }
}
