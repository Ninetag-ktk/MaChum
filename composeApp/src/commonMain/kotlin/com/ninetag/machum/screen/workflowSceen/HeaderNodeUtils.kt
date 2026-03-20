package com.ninetag.machum.screen.workflowSceen

import com.ninetag.machum.entity.HeaderNode
import java.util.IdentityHashMap
import kotlin.math.abs

fun computeDropTarget(
    pointerY: Float,
    draggingNode: HeaderNode,
    nodePositions: Map<HeaderNode, Float>,
    flatNodes: List<HeaderNode>,
): DropTarget? {
    val degree = 24f
    var closestNode: HeaderNode? = null
    var closestDist = Float.MAX_VALUE

    for (candidate in flatNodes) {
        if (candidate === draggingNode) continue
        val candidateY = nodePositions[candidate] ?: continue
        val dist = abs(pointerY - candidateY)
        if (dist < closestDist) {
            closestDist = dist
            closestNode = candidate
        }
    }

    val targetNode = closestNode ?: return null
    val targetY = nodePositions[targetNode] ?: return null

    val zone = when {
        pointerY < targetY - degree -> DropZone.Above
        pointerY > targetY + degree -> DropZone.Below
        else -> DropZone.Inside
    }
    return DropTarget(node = targetNode, zone = zone)
}

fun moveNode(
    node: HeaderNode,
    dropTarget: DropTarget,
    rootNodes: MutableList<HeaderNode>
) {
    // 자기 자신으로 이동 방지
    if (node == dropTarget.node) return
    // 자기 자신의 하위 노드로 이동 방지
    if (isAncestor(node, dropTarget.node)) return

    val (newParent, newIndex) = resolveParentAndIndex(dropTarget, rootNodes)

    // 기존 위치 정보
    val oldParent = node.parent
    val oldList = oldParent?.children ?: rootNodes
    val oldIndex = oldList.indexOf(node)

    // 같은 위치로 이동 시도 시 무시 _ early return
    if (oldParent === newParent && oldIndex == newIndex) return

    // 기존 위치에서 제거
    oldList.remove(node)

    // 새 위치에 삽입
    val newList = newParent?.children ?: rootNodes
    val safeIndex = newIndex.coerceIn(0, newList.size)
    newList.add(safeIndex, node)

    // parent 업데이트
    node.parent = newParent
    // 하위 노드 level 재조정
    adjustLevelsWithLimit(node, (newParent?.level ?: 0) + 1)
}

private fun isAncestor(ancestor: HeaderNode, target: HeaderNode): Boolean {
    var current: HeaderNode? = target.parent
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent
    }
    return false
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

private fun adjustLevelsWithLimit(node: HeaderNode, newLevel: Int) {
    node.level = newLevel.coerceAtMost(4)
    node.children.forEach { child ->
        adjustLevelsWithLimit(child, newLevel + 1)
    }
}

// 드래그 중 collapse 된 요소를 제외하고 순회할 수 있게끔 구성
fun flattenTree(
    nodes: List<HeaderNode>,
    collapsedNodeIds: Set<String>,
    nodeIdMap: IdentityHashMap<HeaderNode, String>
): List<HeaderNode> {
    val result = mutableListOf<HeaderNode>()
    for (node in nodes) {
        result.add(node)
        val nodeId = nodeIdMap[node]
        // 이 노드가 접혀있으면 자식 스킵
        if (nodeId !in collapsedNodeIds && node.children.isNotEmpty()) {
            result.addAll(flattenTree(node.children, collapsedNodeIds, nodeIdMap))
        }
    }
    return result
}

fun deleteNode(
    node: HeaderNode,
    rootNodes: MutableList<HeaderNode>
) {
    val parent = node.parent
    if (parent != null) {
        parent.children.remove(node)
    } else {
        rootNodes.remove(node)
    }
}