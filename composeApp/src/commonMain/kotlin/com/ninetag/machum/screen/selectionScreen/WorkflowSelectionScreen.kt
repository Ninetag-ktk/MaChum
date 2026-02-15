package com.ninetag.machum.screen.selectionScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ninetag.machum.external.FileManager
import org.koin.compose.koinInject

@Composable
fun WorkflowSelectionScreen() {
    val fileManager = koinInject<FileManager>()
    val workflowList by fileManager.workflowList.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.BottomCenter
    ) {

    }
}