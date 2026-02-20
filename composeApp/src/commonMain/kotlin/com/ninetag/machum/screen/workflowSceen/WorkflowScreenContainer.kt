package com.ninetag.machum.screen.workflowSceen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import org.koin.compose.koinInject

@Composable
fun WorkflowScreen(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val workflowList by fileManager.workflowList.collectAsState()

    var workflow by remember { mutableStateOf<PlatformFile?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        workflow
            ?.let{ WorkflowEditScreen(it, { workflow = null }) }
            ?:run{ WorkflowListScreen(workflowList, { workflow = it }) }

        if (show) {
            FloatingActionButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.ArrowBack,
                    contentDescription = "Back",
                )
            }
        }
    }
}