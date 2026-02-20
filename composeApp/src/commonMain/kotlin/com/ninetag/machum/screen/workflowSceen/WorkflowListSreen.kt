package com.ninetag.machum.screen.workflowSceen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.common.WorkflowListItem
import com.ninetag.machum.screen.common.paddingDefault
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun WorkflowListScreen(
    workflowList: List<PlatformFile>,
    onClick: (PlatformFile) -> (Unit)
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // 사전설정 목록 갱신
        fileManager.getWorkflowList()
    }

    Column(
        modifier = Modifier
            .paddingDefault()
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (workflowList.isNotEmpty()) {
            workflowList.forEach { item ->
                WorkflowListItem(
                    workflow = item,
                    onClick = { onClick(item) }
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(
                        color = MaterialTheme.colorScheme.primary,
                    ),
                    onClick = {
                        scope.launch {
//                            fileManager.createFile()
                        }
                    }
                ),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AddCircleOutline,
                    contentDescription = "Add Workflow",
                )
            }
        }
    }
}