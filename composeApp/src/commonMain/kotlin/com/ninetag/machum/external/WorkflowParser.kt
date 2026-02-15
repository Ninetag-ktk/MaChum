package com.ninetag.machum.external

import com.ninetag.machum.entity.HeaderNode
import com.ninetag.machum.entity.WorkflowStep

class WorkflowParser {
    fun parse(markdown: String): List<WorkflowStep> {
        val headerNodes = buildHeaderTree(markdown)
        return extractLeafStep(headerNodes)
    }
    fun buildHeaderTree(markdown: String): List<HeaderNode> {
        val lines = markdown.lines()
        val rootNodes = mutableListOf<HeaderNode>()
        val nodeStack = mutableListOf<HeaderNode>()

        var currentNode: HeaderNode? = null
        val descriptionLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()

            // 콜아웃 감지(설명)
            if (trimmed.startsWith(">")) {
                val quoteLine = trimmed.removePrefix(">").trim()
                descriptionLines.add(quoteLine)
                continue
            }

            // 헤더 감지
            val headerMatch = Regex("""^(#{1,4})\s+(.+)$""").find(trimmed)
            if (headerMatch != null) {
                // 이전 노드에 설명 저장
                currentNode?.let {
                    it.description = descriptionLines.joinToString("\n")
                    descriptionLines.clear()
                }

                val level = headerMatch.groupValues[1].length
                val title = headerMatch.groupValues[2].trim()

                val newNode = HeaderNode(level, title)

                // nodeStack에서 부모 여부 검증
                while (nodeStack.isNotEmpty() && nodeStack.last().level >= level) {
                    nodeStack.removeLast()
                }

                if (nodeStack.isEmpty()) {
                    rootNodes.add(newNode)
                } else {
                    val parent = nodeStack.last()
                    parent.children.add(newNode)
                    newNode.parent = parent
                }
                nodeStack.add(newNode)
                currentNode = newNode
            }
        }

        // 마지막 노드 설명 저장
        currentNode?.let {
            it.description = descriptionLines.joinToString("\n")
        }

        return rootNodes
    }

    // 리프노드 탐색 및 넘버링
    private fun extractLeafStep(roots: List<HeaderNode>): List<WorkflowStep> {
        val steps = mutableListOf<WorkflowStep>()

        fun traverse(node: HeaderNode, ancestorPath: List<Int>) {
            if (node.children.isEmpty()) {
                // 리프노트 넘버링 생성
                val numbering = ancestorPath.joinToString("-")
                steps.add(WorkflowStep(numbering, node.title))
            } else {
                // 중간 노드: 자식 탐색
                node.children.forEachIndexed { index, child ->
                    val newPath = if (node.children.size > 1) {
                        ancestorPath + (index + 1)
                    } else {
                        ancestorPath
                    }
                    traverse(child, newPath)
                }
            }
        }

        // 루트 레벨 순회
        roots.forEachIndexed { index, root ->
            val rootPath = if (roots.size > 1) listOf(index + 1) else emptyList()
            traverse(root, rootPath)
        }
        return steps
    }
}