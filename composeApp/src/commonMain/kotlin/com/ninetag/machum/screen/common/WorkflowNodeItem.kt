package com.ninetag.machum.screen.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ninetag.machum.entity.HeaderNode
import com.ninetag.machum.screen.workflowSceen.DropZone
import com.ninetag.machum.screen.workflowSceen.DragState
import kotlinx.coroutines.delay
import java.util.IdentityHashMap
import java.util.UUID

@Composable
fun WorkflowNodeItem(
    node: HeaderNode,
    onNodeChanged: (HeaderNode) -> Unit = {},
    childExpandTrigger: Boolean,
    onChildExpandTrigger: () -> Unit = {},
    descriptionExpandTrigger: Boolean? = null,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    isDraggingActive: Boolean,
    isDropTargetAbove: Boolean,
    isDropTargetInside: Boolean,
    isDropTargetBelow: Boolean,
    onPositionChanged: (Float) -> Unit,
    onDragStart: () -> Unit,
    onFocusGained: () -> Unit = {},
    shouldRequestFocus: Boolean = false,
    onFocusRequested: () -> Unit = {},
) {
    var title by remember(node) { mutableStateOf(node.title) }
    var description by remember(node) { mutableStateOf(node.description) }
    val focusRequester = remember { FocusRequester() }

    val depth = node.level - 1
    val indent = (depth * 24).dp
    
    var nodeYInParent by remember { mutableStateOf(0f) } // LazyColumn 기준 좌표

    var isDescriptionExpanded by remember(node) { mutableStateOf(
        if (node.description.isBlank()) false
        else descriptionExpandTrigger?:node.description.isNotBlank()
    ) }
    var descriptionHeight by remember { mutableStateOf(0) }
    
    val expandIconRotation by animateFloatAsState(
        targetValue = if (childExpandTrigger) 90f else 0f,
        animationSpec = tween(
            durationMillis = 100,
            easing = FastOutSlowInEasing
        ),
        label = "Icon Rotation",
    )

    LaunchedEffect(descriptionExpandTrigger) {
        if (node.description.isBlank()) isDescriptionExpanded = false
        else descriptionExpandTrigger?.let {
            isDescriptionExpanded = (it && node.description.isNotBlank())
        }
    }

    LaunchedEffect(shouldRequestFocus) {
        if (shouldRequestFocus) {
            delay(100)
            focusRequester.requestFocus()
            onFocusRequested()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isDragging) 0.5f else 1f)
            .onGloballyPositioned { coords ->
                nodeYInParent = coords.positionInParent().y
                onPositionChanged(nodeYInParent) // 드롭 타겟용
            },
    ) {
        DropIndicatorLine(visible = isDropTargetAbove, indent = indent)

        Column(
            modifier = modifier.fillMaxWidth().wrapContentHeight()
                .background(
                    color = if (isDropTargetInside) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { _, _ ->},
                    )
                }
        ) {
            // Depth 인디케이터
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(depth) {
                    Spacer(Modifier.width(11.dp))
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(11.dp))
                }
                if (node.children.isNotEmpty()) {
                    Box (
                        modifier = Modifier.height(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = { onChildExpandTrigger() },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = if (childExpandTrigger) "Expand" else "Shrink",
                                modifier = Modifier.rotate(expandIconRotation)
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.width(24.dp))
                }
                BasicTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        node.title = it
                        onNodeChanged(node)
                    },
                    modifier = Modifier.weight(1f).padding(4.dp, 0.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) onFocusGained()
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.all { it.pressed && it.previousPressed }) {
                                        event.changes.forEach { it.consume() }
                                        break
                                    }
                                }
                            }
                        },
                    enabled = !isDragging,
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box {
                            if (title.isEmpty()) {
                                Text(
                                    text = "공정을 입력하세요",
                                    style = LocalTextStyle.current.copy(
                                        fontSize = 14.sp,
                                        lineHeight = 14.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                IconButton(
                    onClick = { isDescriptionExpanded = !isDescriptionExpanded },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = "DescriptionToggle",
                    )
                }
            }
            AnimatedVisibility (isDescriptionExpanded && !isDraggingActive ) {
                Row {
                    repeat(depth) {
                        Spacer(Modifier.width(11.dp))
                        VerticalDivider(
                            modifier = Modifier.height(with(LocalDensity.current){descriptionHeight.toDp()} + 8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(11.dp))
                    }
                    if (node.children.isNotEmpty()) {
                        Spacer(Modifier.width(11.dp))
                        VerticalDivider(
                            modifier = Modifier.height(with(LocalDensity.current){descriptionHeight.toDp()} + 8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(11.dp))
                    } else {
                        Spacer(Modifier.width(24.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = {
                            description = it
                            node.description = it
                            onNodeChanged(node)
                        },
                        modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(4.dp)
                            .onGloballyPositioned { coords ->
                                descriptionHeight = coords.size.height
                            }
                            .onFocusChanged { focusState ->
                                if (focusState.hasFocus) onFocusGained()
                            },
                        enabled = !isDragging,
                        textStyle = TextStyle(fontSize = 14.sp),
                        placeholder = {
                            Text(
                                text = "공정에 대한 설명을 입력하세요",
                                fontSize = 14.sp,
                            ) },
                    )
                }
            }
        }
    }

    DropIndicatorLine(visible = isDropTargetBelow, indent = indent)
}

fun LazyListScope.renderTreeItems(
    nodes: List<HeaderNode>,
    nodeIdMap: IdentityHashMap<HeaderNode, String>,
    onNodeChanged: (HeaderNode) -> Unit,
    collapsedNode: Set<String>,
    onChildExpandToggle: (nodeId: String) -> Unit,
    descriptionExpandTrigger: Boolean?,
    dragState: DragState?,
    isDraggingActive: Boolean,
    onPositionChanged: (HeaderNode, Float) -> Unit,
    onDragStart: (HeaderNode) -> Unit,
    onFocusGained: (nodeId: String) -> Unit = {},
    pendingFocusNodeId: String? = null,
    onFocusRequested: () -> Unit = {},
) {
    nodes.forEach { node ->
        val nodeId = nodeIdMap[node] ?: return@forEach

        item(key = nodeIdMap[node]) {
            val isCollapsed = collapsedNode.contains(nodeId)
            WorkflowNodeItem(
                node = node,
                onNodeChanged = onNodeChanged,
                childExpandTrigger = !isCollapsed,
                onChildExpandTrigger = { onChildExpandToggle(nodeId) },
                descriptionExpandTrigger = descriptionExpandTrigger,
                modifier = Modifier.animateItem(
                    placementSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessHigh
                    )
                ),
                isDragging = dragState?.draggingNode === node,
                isDraggingActive = isDraggingActive,
                isDropTargetAbove = dragState?.dropTargetNode === node && dragState.dropZone == DropZone.Above,
                isDropTargetInside = dragState?.dropTargetNode === node && dragState.dropZone == DropZone.Inside,
                isDropTargetBelow = dragState?.dropTargetNode === node && dragState.dropZone == DropZone.Below,
                onPositionChanged = { y -> onPositionChanged(node, y) },
                onDragStart = { onDragStart(node) },
                onFocusGained = { onFocusGained(nodeId) },
                shouldRequestFocus = pendingFocusNodeId == nodeId,
                onFocusRequested = onFocusRequested,
            )
        }
        if (!collapsedNode.contains(nodeId) && node.children.isNotEmpty()) {
            renderTreeItems(
                nodes = node.children,
                nodeIdMap = nodeIdMap,
                onNodeChanged = onNodeChanged,
                collapsedNode = collapsedNode,
                onChildExpandToggle = onChildExpandToggle,
                descriptionExpandTrigger = descriptionExpandTrigger,
                dragState = dragState,
                isDraggingActive = isDraggingActive,
                onPositionChanged = onPositionChanged,
                onDragStart = onDragStart,
                onFocusGained = onFocusGained,
                pendingFocusNodeId = pendingFocusNodeId,
                onFocusRequested = onFocusRequested,
            )
        }
    }
}

fun assignIds(
    nodes: List<HeaderNode>,
    idMap: IdentityHashMap<HeaderNode, String>
) {
    nodes.forEach { node ->
        if (node !in idMap) {
            idMap[node] = UUID.randomUUID().toString()
        }
        if (node.children.isNotEmpty()) {
            assignIds(node.children, idMap)
        }
    }
}

@Composable
fun DragGhostCard(
    title: String,
    pointerY: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(200f)
            .offset(y = with(LocalDensity.current) {pointerY.toDp()} - 20.dp)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(8.dp))
                .background(
                    color = MaterialTheme.colorScheme.primaryFixedDim,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.ifBlank {"(noTitle)"},
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun DropIndicatorLine(visible: Boolean, indent: Dp) {
    AnimatedVisibility(visible) {
        Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            Spacer(Modifier.width(indent))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}