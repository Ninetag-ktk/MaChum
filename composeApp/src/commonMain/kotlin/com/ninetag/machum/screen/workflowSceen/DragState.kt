package com.ninetag.machum.screen.workflowSceen

import com.ninetag.machum.entity.HeaderNode


data class DragState(
    val draggingNode: HeaderNode,
    val dropTargetNode: HeaderNode?,
    val dropZone: DropZone?,
)

enum class DropZone { Above, Inside, Below }

data class DropTarget(
    val node: HeaderNode,
    val zone: DropZone,
)
