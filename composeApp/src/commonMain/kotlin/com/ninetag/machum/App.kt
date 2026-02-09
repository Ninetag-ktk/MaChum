package com.ninetag.machum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.TestScreen
import com.ninetag.machum.screen.selectionScreen.VaultSelectionScreen

import org.koin.compose.koinInject

@Composable
@Preview
fun App() {
    val fileManager = koinInject<FileManager>()
    val bookmark by fileManager.bookmarks.collectAsState()
    val scope = rememberCoroutineScope()

    var showVaultPicker by remember { mutableStateOf(false) }

    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
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
//                Text("확인: 북마크=${bookmark!=null}, Vault=${bookmark?.vaultData != null}, show=${showVaultPicker}")
            bookmark?.let {
                when {
                    it.vaultData == null || showVaultPicker -> { VaultSelectionScreen(reset = { showVaultPicker = false }) }
//                        it.projectData == null -> { ProjectSelectionScreen() }
//                        it.fileData == null -> { scope.launch { fileManager.pickProject(it.projectData) } }
                    else -> {
                        TestScreen()
                    }
                }
            }
        }
    }
}