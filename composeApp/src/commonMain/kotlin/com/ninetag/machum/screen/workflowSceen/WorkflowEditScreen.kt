package com.ninetag.machum.screen.workflowSceen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ninetag.machum.entity.HeaderNode
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.WorkflowParser
import com.ninetag.machum.external.getDescription
import com.ninetag.machum.screen.common.DragGhostCard
import com.ninetag.machum.screen.common.assignIds
import com.ninetag.machum.screen.common.paddingDefault
import com.ninetag.machum.screen.common.renderTreeItems
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.IdentityHashMap

@Composable
fun WorkflowEditScreen(
    workflow: PlatformFile,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    val fileManager = koinInject<FileManager>()
    val workflowParser = WorkflowParser()

    var currentWorkflow by remember { mutableStateOf(workflow) }
    var workflowTitle by remember { mutableStateOf("") }
    var workflowDescription by remember { mutableStateOf("") }
    val workflowSteps = remember { mutableStateListOf<HeaderNode>() }
    val nodeIdMap = remember { IdentityHashMap<HeaderNode, String>() }

    var isMenuExpanded by remember { mutableStateOf(false) }
    var collapsedNodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isEditingDescription by remember { mutableStateOf(false) }
    var isDescriptionExpended by remember { mutableStateOf<Boolean?>(null) }
    var isDraggingActive by remember { mutableStateOf(false) }

    var dragState by remember { mutableStateOf<DragState?>(null) }
    val nodePositions = remember { mutableStateMapOf<HeaderNode, Float>() }
    var currentPointerY by remember { mutableStateOf<Float?>(null) }

    val density = LocalDensity.current
    val lazyListState = rememberLazyListState()
    val autoScrollThreshold = 200.dp
    val thresholdPx = with(density) { autoScrollThreshold.toPx() }

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
                        debounceJob?.cancel()
                        debounceJob = scope.launch {
                            delay(500)
                            fileManager.renameWorkflow(currentWorkflow, it)?.let{ newWorkflow ->
                                currentWorkflow = newWorkflow
                            }
                            isMenuExpanded = false
                        }
                    },
                    isMenuExpended = isMenuExpanded,
                    onMenuToggle = { isMenuExpanded = !isMenuExpanded },
                    onDescriptionEdit = {
                        isEditingDescription = !isEditingDescription
                    },
                    onDelete = {
                        scope.launch {
                            fileManager.delete(workflow)
                            onDismiss()
                        }
                    },
                    onBackClick = {
                        scope.launch {
                            val content = workflowParser.toMarkdown(
                                description = workflowDescription,
                                nodes = workflowSteps,
                            )
                            fileManager.write(currentWorkflow, content)
                            onDismiss()
                        }
                    },
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

        // HeaderNodeTree 범위
        BoxWithConstraints(
            modifier = Modifier
                .paddingDefault()
                .fillMaxSize()
                .pointerInput(dragState) {
                    // drag를 시작한 WorkflowNodeItem이 존재할 때 그 인터랙션을 이어받음
                    if (dragState != null) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Move -> {
                                        event.changes.forEach { change ->
                                            change.consume()

                                            // 현재 포인터 위치 (BoxWithConstraints 기준)
                                            val absolutePointerY = change.position.y
                                            currentPointerY = absolutePointerY

                                            // 스크롤 오프셋 계산
                                            val scrollOffset = lazyListState.firstVisibleItemScrollOffset.toFloat()

                                            // LazyColumn 내부 좌표로 변환
                                            val relativePointerY = absolutePointerY + scrollOffset

                                            // 드롭 타겟 계산
                                            val current = dragState ?: return@awaitPointerEventScope
                                            val flatNodes = flattenTree(workflowSteps, collapsedNodes, nodeIdMap,)

                                            computeDropTarget(relativePointerY, current.draggingNode, nodePositions, flatNodes)
                                                ?.let { target ->
                                                    dragState = current.copy(
                                                        dropTargetNode = target.node,
                                                        dropZone = target.zone,
                                                    )
                                                }
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        val current = dragState
                                        if (current != null) {
                                            val target = current.dropTargetNode
                                            val zone = current.dropZone
                                            if (target != null && zone != null) {
                                                moveNode(
                                                    node = current.draggingNode,
                                                    dropTarget = DropTarget(node = target, zone = zone),
                                                    rootNodes = workflowSteps
                                                )
                                            }
                                        }
                                        isDraggingActive = false
                                        currentPointerY = null
                                        dragState = null
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            val screenHeight = with(density) { maxHeight.toPx() }

            LaunchedEffect(dragState) {
                while (dragState != null) {
                    val pointerY = currentPointerY
                    if (pointerY != null) {
                        when {
                            pointerY < thresholdPx -> {
                                lazyListState.scrollBy(-15f)
                            }
                            pointerY > screenHeight - thresholdPx -> {
                                lazyListState.scrollBy(15f)
                            }
                        }
                    }
                    delay(30)
                }
            }

            // HeaderNode 리스트
            LazyColumn(
                state = lazyListState,
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
                    dragState = dragState,
                    isDraggingActive = isDraggingActive,
                    onPositionChanged = { node, y ->
                        nodePositions[node] = y
                    },
                    onDragStart = { node ->
                        isDraggingActive = true
                        dragState = DragState(
                            draggingNode = node,
                            dropTargetNode = null,
                            dropZone = null
                        )
                    },
                )
            }
        }

        // DragGhostCard
        dragState?.let { state ->
            currentPointerY?.let { pointerY ->
                DragGhostCard(
                    title = state.draggingNode.title,
                    pointerY = pointerY,
                )
            }
        }
    }
}