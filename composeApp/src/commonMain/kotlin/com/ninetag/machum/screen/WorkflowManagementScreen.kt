package com.ninetag.machum.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninetag.machum.entity.HeaderNode
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.WorkflowParser
import com.ninetag.machum.external.getDescription
import com.ninetag.machum.screen.common.CustomListItem
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun WorkflowManagementScreen(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val workflowList by fileManager.workflowList.collectAsState()

    var workflow by remember { mutableStateOf<PlatformFile?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        workflow
            ?.let{ WorkflowEditScreen(it) }
            ?:run{ WorkflowListScreen(workflowList, { workflow = it }) }

        if (show) {
            FloatingActionButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.BottomStart),
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

@Composable
internal fun WorkflowEditScreen(workflow: PlatformFile) {
    val workflowParser = WorkflowParser()
    var workflowDescription by remember { mutableStateOf("") }
    val workflowSteps = remember { mutableStateListOf<HeaderNode>() }

    LaunchedEffect(Unit) {
        workflowDescription = workflow.getDescription()
        val parseNodes = workflowParser.buildHeaderTree(workflow.readString())
        workflowSteps.clear()
        workflowSteps.addAll(parseNodes)
    }
}

@Composable
internal fun WorkflowListScreen(
    workflowList: List<PlatformFile>,
    onClick: (PlatformFile) -> (Unit)
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // 사전설정
        fileManager.getWorkflowList()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        if (workflowList.isNotEmpty()) {
            workflowList.forEach { item ->
                WorkflowCard(
                    workflow = item,
                    onClick = { onClick(item) }
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().height(40.dp)
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

@Composable
internal fun WorkflowCard(
    workflow: PlatformFile,
    onClick: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()
    var showContextMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    var workflowDescription by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        workflowDescription = workflow.getDescription()
    }

    Box {
        CustomListItem(
            isLongPressed = showContextMenu,
            onClick = onClick,
            onContextMenu = { offset ->
                menuPosition = offset
                showContextMenu = true
            },
            modifier = Modifier.height(40.dp),
        ) {
            Text(
                text = workflow.nameWithoutExtension,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = workflowDescription,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                fontSize = 12.sp,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = DpOffset(
                x = with(LocalDensity.current) {menuPosition.x.toDp()},
                y = with(LocalDensity.current) {menuPosition.y.toDp()}
            ),
        ) {
            DropdownMenuItem(
                text = { Text("드롭다운") },
                onClick = {  }
            )
        }
    }
}