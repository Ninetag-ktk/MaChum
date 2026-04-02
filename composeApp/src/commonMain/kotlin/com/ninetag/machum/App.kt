package com.ninetag.machum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.MainScreen
import com.ninetag.machum.screen.projectScreen.ProjectSelectionScreen
import com.ninetag.machum.screen.projectScreen.WorkflowSelectionScreen
import com.ninetag.machum.screen.vaultScreen.VaultSelectionScreen
import com.ninetag.machum.screen.workflowSceen.WorkflowScreen
import com.ninetag.machum.theme.AppTheme
import kotlinx.coroutines.launch

import org.koin.compose.koinInject

@Composable
@Preview
fun App() {
    val fileManager = koinInject<FileManager>()
    val bookmark by fileManager.bookmarks.collectAsState()
    val workflowList by fileManager.workflowList.collectAsState()
    val workflow by fileManager.workflow.collectAsState()
    val scope = rememberCoroutineScope()

    var showVaultPicker by remember { mutableStateOf(false) }
    var showWorkflowManagement by remember { mutableStateOf(false) }

    AppTheme {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    bookmark.vaultData == null || showVaultPicker -> { VaultSelectionScreen(reset = { showVaultPicker = false }) }
                    workflowList.isEmpty() || showWorkflowManagement -> { WorkflowScreen(show = showWorkflowManagement, onDismiss = { showWorkflowManagement = false }) }
                    bookmark.projectData == null -> { ProjectSelectionScreen() }
                    workflow.isEmpty() -> { WorkflowSelectionScreen() }
                    bookmark.fileData == null -> { scope.launch { fileManager.setFile(bookmark.projectData!!) } }
                    else -> { MainScreen() }
                }
            }
        }
    }
}