package com.ninetag.machum.markdown.state

/** 블록 경계를 넘는 selection을 어느 방향으로 확장할지 나타낸다. */
internal enum class SelectionDirection {
    Previous,
    Next,
}

/**
 * 블록 단위 selection 입력을 계산한 결과.
 *
 * Compose 쪽은 이 결과를 실제 [DocumentSelection] 상태와 부모 컨테이너 escape callback에
 * 적용하기만 한다. 이 구분으로 키 이벤트와 selection 결정 규칙을 서로 독립적으로 테스트한다.
 */
internal sealed interface SelectionAdjustment {
    data class Set(val selection: DocumentSelection.Multi) : SelectionAdjustment
    data object EscapeToParent : SelectionAdjustment
    data object Keep : SelectionAdjustment
}

/**
 * Cross-block selection의 UI 비의존 결정 로직.
 *
 * 상태 자체는 문서 수명을 소유하는 `MarkdownBlockTextField`에 남겨 두고, 이 coordinator는
 * 입력 하나에 대한 다음 selection만 계산한다.
 */
internal object EditorSelectionCoordinator {

    fun selectAll(blocks: List<EditorBlock>): DocumentSelection.Multi? {
        val first = blocks.firstOrNull() ?: return null
        val last = blocks.lastOrNull() ?: return null
        return DocumentSelection.Multi(
            anchor = startEndpointOf(first, emptyList()),
            focus = endEndpointOf(last, emptyList()),
        )
    }

    /** 이미 시작된 Multi selection의 focus를 현재 컨테이너 안에서 한 블록 이동한다. */
    fun extendExisting(
        rootBlocks: List<EditorBlock>,
        selection: DocumentSelection.Multi,
        direction: SelectionDirection,
    ): DocumentSelection.Multi? {
        val focus = nextFocusEndpoint(
            blocks = rootBlocks,
            focus = selection.focus,
            down = direction == SelectionDirection.Next,
        ) ?: return null
        return DocumentSelection.Multi(selection.anchor, focus)
    }

    /**
     * 한 블록의 native selection에서 인접 블록으로 document selection을 시작한다.
     * 컨테이너 경계에서는 부모 승격 여부만 결과로 돌려준다.
     */
    fun extendFromBlock(
        currentBlock: EditorBlock,
        currentIndex: Int,
        blocksInContainer: List<EditorBlock>,
        containerPath: List<String>,
        currentSelection: DocumentSelection,
        direction: SelectionDirection,
    ): SelectionAdjustment {
        val targetIndex = when (direction) {
            SelectionDirection.Previous -> currentIndex - 1
            SelectionDirection.Next -> currentIndex + 1
        }
        if (targetIndex !in blocksInContainer.indices) {
            return if (containerPath.isNotEmpty()) {
                SelectionAdjustment.EscapeToParent
            } else {
                SelectionAdjustment.Keep
            }
        }

        val target = blocksInContainer[targetIndex]
        if (isAtomic(target)) {
            return SelectionAdjustment.Set(selectAtomic(target, containerPath))
        }

        val existingAnchor = (currentSelection as? DocumentSelection.Multi)?.anchor
        val anchor = existingAnchor ?: when (direction) {
            SelectionDirection.Previous -> endEndpointOf(currentBlock, containerPath)
            SelectionDirection.Next -> startEndpointOf(currentBlock, containerPath)
        }
        val focus = when (direction) {
            SelectionDirection.Previous -> startEndpointOf(target, containerPath)
            SelectionDirection.Next -> endEndpointOf(target, containerPath)
        }
        return SelectionAdjustment.Set(DocumentSelection.Multi(anchor, focus))
    }

    fun selectAtomic(
        block: EditorBlock,
        containerPath: List<String>,
    ): DocumentSelection.Multi = DocumentSelection.Multi(
        anchor = startEndpointOf(block, containerPath),
        focus = endEndpointOf(block, containerPath),
    )

    /** endpoint 블록으로 파생 focus가 이동하는 경우에는 기존 selection을 보존한다. */
    fun shouldClearOnFocus(selection: DocumentSelection, blockId: String): Boolean {
        val multi = selection as? DocumentSelection.Multi ?: return false
        return multi.anchor.blockId != blockId && multi.focus.blockId != blockId
    }

    private fun startEndpointOf(
        block: EditorBlock,
        containerPath: List<String>,
    ): SelectionEndpoint = SelectionEndpoint(
        containerPath = containerPath,
        blockId = block.id,
        offset = SelectionEndpoint.ATOMIC_START,
    )

    private fun endEndpointOf(
        block: EditorBlock,
        containerPath: List<String>,
    ): SelectionEndpoint = SelectionEndpoint(
        containerPath = containerPath,
        blockId = block.id,
        offset = when (block) {
            is EditorBlock.Text -> block.textFieldState.text.length
            else -> SelectionEndpoint.ATOMIC_END
        },
    )
}
