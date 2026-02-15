package com.ninetag.machum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.TestScreen
import com.ninetag.machum.screen.selectionScreen.ProjectSelectionScreen
import com.ninetag.machum.screen.selectionScreen.VaultSelectionScreen
import com.ninetag.machum.screen.WorkflowManagementScreen
import com.ninetag.machum.screen.selectionScreen.WorkflowSelectionScreen
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

    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
//            Button(onClick = { showContent = !showContent }) {
//                Text("Click me!")
//            }
//            AnimatedVisibility(showContent) {
//                val greeting = remember { Greeting().greet() }
//                Column(
//                    modifier = Modifier.fillMaxWidth(),
//                    horizontalAlignment = Alignment.CenterHorizontally,
//                ) {
//                    Image(painterResource(Res.drawable.compose_multiplatform), null)
//                    Text("Compose: $greeting")
//                }
//            }
//                TestScreen()
//            Text("확인: 북마크=${bookmark!=null}, Vault=${bookmark?.vaultData != null}, Project=${bookmark?.projectData != null}, File=${bookmark?.fileData != null}")
            bookmark?.let {
                when {
                    it.vaultData == null || showVaultPicker -> { VaultSelectionScreen({ showVaultPicker = false }) }
                    workflowList.isEmpty() || showWorkflowManagement -> { WorkflowManagementScreen(showWorkflowManagement, { showWorkflowManagement = false }) }
                    it.projectData == null -> { ProjectSelectionScreen() }
                    workflow.isEmpty() -> { WorkflowSelectionScreen() }
                    it.fileData == null -> { scope.launch { fileManager.setFile(it.projectData) } }
                    else -> {
                        TestScreen()
                    }
                }
            }
        }
    }
}