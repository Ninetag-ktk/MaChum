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
import com.ninetag.machum.screen.vaultScreen.VaultSelectionScreen
import com.ninetag.machum.theme.AppTheme
import kotlinx.coroutines.launch

import org.koin.compose.koinInject

@Composable
@Preview
fun App() {
    val fileManager = koinInject<FileManager>()
    val bookmark by fileManager.bookmarks.collectAsState()

    var showVaultPicker by remember { mutableStateOf(false) }

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
                    bookmark.projectData == null -> { ProjectSelectionScreen() }
                    bookmark.fileData == null -> { LaunchedEffect(bookmark.projectData) { fileManager.setFile(bookmark.projectData!!) } }
                    else -> { MainScreen() }
                }
            }
        }
    }
}