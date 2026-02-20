package com.ninetag.machum.screen.workflowSceen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ninetag.machum.entity.HeaderNode
import com.ninetag.machum.external.WorkflowParser
import com.ninetag.machum.external.getDescription
import com.ninetag.machum.screen.common.assignIds
import com.ninetag.machum.screen.common.paddingDefault
import com.ninetag.machum.screen.common.renderTreeItems
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.readString
import java.util.IdentityHashMap

@Composable
fun WorkflowEditScreen(
    workflow: PlatformFile,
    onDismiss: () -> Unit,
) {
    val workflowParser = WorkflowParser()

    var workflowTitle by remember { mutableStateOf("") }
    var workflowDescription by remember { mutableStateOf("") }
    val workflowSteps = remember { mutableStateListOf<HeaderNode>() }
    val nodeIdMap = remember { IdentityHashMap<HeaderNode, String>() }

    var isMenuExpanded by remember { mutableStateOf(false) }
    var collapsedNodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isEditingDescription by remember { mutableStateOf(false) }
    var isDescriptionExpended by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        workflowTitle = workflow.nameWithoutExtension
        workflowDescription = workflow.getDescription()
        val parseNodes = workflowParser.buildHeaderTree(workflow.readString())
        workflowSteps.clear()
        workflowSteps.addAll(parseNodes)

        // ID 할당
        assignIds(parseNodes, nodeIdMap)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // HeaderNodeTree 범위
        Box(modifier = Modifier
            .paddingDefault()
            .fillMaxSize()) {
            // HeaderNode 리스트
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 96.dp, bottom = 72.dp,),
            ) {
                renderTreeItems(
                    nodes = workflowSteps,
                    nodeIdMap = nodeIdMap,
                    onNodeChanged = {},
                    collapsedNode = collapsedNodes,
                    onChildExpandToggle = { nodeId ->
                        collapsedNodes = if (collapsedNodes.contains(nodeId)) {
                            collapsedNodes - nodeId
                        } else {
                            collapsedNodes + nodeId
                        }
                    },
                    descriptionExpandTrigger = isDescriptionExpended,
                )
            }
        }

        // UI Header 와 Description 수정 UI
        Box(
            modifier = Modifier.fillMaxWidth().wrapContentHeight().zIndex(99f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isMenuExpanded) {
                            Modifier.pointerInput(Unit) { detectTapGestures (onTap = {
                                isMenuExpanded = false
                                isEditingDescription = false
                            }) }
                        } else { Modifier }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 헤더
                WorkflowEditHeader(
                    title = workflowTitle,
                    onTitleChange = {
                        workflowTitle = it
                        isMenuExpanded = false
                    },
                    isMenuExpended = isMenuExpanded,
                    onMenuToggle = { isMenuExpanded = !isMenuExpanded },
                    onDescriptionEdit = {
                        isEditingDescription = !isEditingDescription
                    },
                    onDelete = {
                        // Todo 삭제 로직
                    },
                    onBackClick = onDismiss,
                )
                AnimatedVisibility(
                    visible = isEditingDescription,
                ) {
                    WorkflowDescriptionEditor(
                        description = workflowDescription,
                        onDescriptionChange = { workflowDescription = it },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // HeaderNode 컨트롤 메뉴 버튼
                Row(
                    modifier = Modifier.wrapContentWidth().height(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        modifier = Modifier.size(40.dp),
                        onClick = {
                            if (collapsedNodes.isEmpty()) {
                                collapsedNodes = nodeIdMap.values.toSet()
                            } else {
                                collapsedNodes = emptySet()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (collapsedNodes.isEmpty()) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                            contentDescription = "GlobalChildTrigger",
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(40.dp),
                        onClick = { isDescriptionExpended
                            ?.let { isDescriptionExpended = !it }
                            ?:run { isDescriptionExpended = false } },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = "GlobalDescriptionToggle",
                        )
                    }
                }
            }
        }
    }
}

private fun resolveParentAndIndex(
    target: DropTarget,
    rootNodes: List<HeaderNode>
): Pair<HeaderNode?, Int> {
    return when (target.zone) {
        DropZone.Inside -> {
            val index = target.node.children.size
            Pair(target.node, index)
        }
        DropZone.Above -> {
            val parent = target.node.parent
            val list = parent?.children ?: rootNodes
            val index = list.indexOfFirst { it === target.node }.coerceAtLeast(0)
            Pair(parent, index)
        }
        DropZone.Below -> {
            val parent = target.node.parent
            val list = parent?.children ?: rootNodes
            val index = (list.indexOfFirst { it === target.node } + 1).coerceAtLeast(0)
            Pair(parent, index)
        }
    }
}