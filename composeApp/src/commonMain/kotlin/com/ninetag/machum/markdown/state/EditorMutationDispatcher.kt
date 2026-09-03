package com.ninetag.machum.markdown.state

/**
 * 한 번의 구조 편집 결과.
 *
 * history와 selection 변경은 아직 포함하지 않는다. 1차 dispatcher는 기존 [BlockOperations] 결과와
 * 후속 포커스 의도만 묶어 UI callback의 중복 적용 코드를 제거한다.
 */
internal data class EditorMutation(
    val blocks: List<EditorBlock>,
    val focusIntent: EditorFocusIntent? = null,
)

/** 기존 [BlockOperations]를 재사용하는 얇은 구조 편집 dispatcher. */
internal object EditorMutationDispatcher {
    fun mergeWithPrevious(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): EditorMutation? {
        BlockOperations.mergeWithPrevious(blocks, blockIndex)?.let { return it.toMutation() }

        // 일반 merge가 적용되지 않으면 직전 특수 블록을 raw Text로 전환한다.
        return BlockOperations.dissolveSpecial(blocks, blockIndex - 1)?.toMutation()
    }

    fun splitTextBlock(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): EditorMutation? = BlockOperations.trySplitTextBlock(blocks, blockIndex)?.toMutation()

    fun splitByEmptyLine(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): EditorMutation? = BlockOperations.trySplitByEmptyLine(blocks, blockIndex)?.toMutation()

    fun reparse(
        blocks: List<EditorBlock>,
        blockIndex: Int,
        excludeCalloutTypes: Set<String> = emptySet(),
        requestFocus: Boolean = true,
    ): EditorMutation? = BlockOperations
        .tryReparse(blocks, blockIndex, excludeCalloutTypes)
        ?.toMutation(requestFocus)

    fun dissolve(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): EditorMutation? = BlockOperations.dissolveSpecial(blocks, blockIndex)?.toMutation()

    fun clearRawMode(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): EditorMutation? {
        val current = blocks.getOrNull(blockIndex) as? EditorBlock.Text ?: return null
        if (!current.rawMode) return null

        val updated = blocks.toMutableList()
        updated[blockIndex] = current.copy(rawMode = false, rawOrigin = null)
        return EditorMutation(blocks = updated)
    }

    private fun SplitResult.toMutation(requestFocus: Boolean = true): EditorMutation {
        val target = newBlocks.getOrNull(focusBlockIndex)
        val focusIntent = if (requestFocus && target != null) {
            EditorFocusIntent(
                targetBlockId = target.id,
                cursorHint = focusCursorOffset?.let(CursorHint::AtOffset),
            )
        } else {
            null
        }
        return EditorMutation(blocks = newBlocks, focusIntent = focusIntent)
    }

    private fun DissolveResult.toMutation(): EditorMutation = EditorMutation(
        blocks = newBlocks,
        focusIntent = EditorFocusIntent(
            targetBlockId = targetBlockId,
            cursorHint = CursorHint.AtOffset(cursorOffset),
        ),
    )
}
