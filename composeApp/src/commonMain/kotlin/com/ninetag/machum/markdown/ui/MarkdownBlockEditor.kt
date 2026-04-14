package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.BlockOperations
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.SplitResult
import com.ninetag.machum.markdown.ui.block.CalloutBlockEditor
import com.ninetag.machum.markdown.ui.block.CodeBlockEditor
import com.ninetag.machum.markdown.ui.block.TableBlockEditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds

/**
 * 블록 간 내비게이션 및 분할/병합 콜백.
 */
data class BlockNavigation(
    val onMoveToPrevious: () -> Unit = {},
    val onMoveToNext: () -> Unit = {},
    val onMoveLeft: () -> Unit = {},
    val onMergeWithPrevious: () -> Unit = {},
    val onSplitBlock: () -> Unit = {},
    val onSplitByEmptyLine: () -> Unit = {},
    val onReparse: () -> Unit = {},
)

/**
 * 블록 리스트를 렌더링하는 에디터 Composable.
 */
@Composable
internal fun MarkdownBlockEditor(
    blocks: List<EditorBlock>,
    onBlocksChanged: (List<EditorBlock>) -> Unit,
    modifier: Modifier = Modifier,
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig(),
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
    isNested: Boolean = false,
    onEscapeToPrevious: () -> Unit = {},
    onEscapeToNext: () -> Unit = {},
    onEscapeLeft: () -> Unit = {},
    firstBlockFocusRequester: FocusRequester? = null,
) {
    // 블록 id → FocusRequester 맵 (블록이 추가/삭제되어도 기존 블록의 requester 유지)
    val focusRequesterMap = remember { mutableMapOf<String, FocusRequester>() }
    // 블록 리스트 변경 시 불필요한 requester 정리
    val currentIds = blocks.map { it.id }.toSet()
    focusRequesterMap.keys.retainAll(currentIds)
    for (block in blocks) {
        focusRequesterMap.getOrPut(block.id) { FocusRequester() }
    }
    // 외부에서 첫 블록의 FocusRequester를 지정한 경우 (Callout body 등)
    if (firstBlockFocusRequester != null && blocks.isNotEmpty()) {
        focusRequesterMap[blocks.first().id] = firstBlockFocusRequester
    }

    // 포커스 지연 요청: 블록 분할/병합/이동 후 대상 블록에 포커스
    // pendingFocusBlockId를 LaunchedEffect key로 사용하면 null 설정 시 effect가 취소되므로,
    // 별도 카운터를 key로 사용한다.
    var pendingFocusBlockId by remember { mutableStateOf<String?>(null) }
    var focusRequestCounter by remember { mutableStateOf(0) }
    LaunchedEffect(focusRequestCounter) {
        val id = pendingFocusBlockId ?: return@LaunchedEffect
        // composition + layout 완료를 기다린 후 포커스 요청
        kotlinx.coroutines.delay(50.milliseconds)
        try {
            focusRequesterMap[id]?.requestFocus()
        } catch (_: IllegalStateException) {
            kotlinx.coroutines.delay(100.milliseconds)
            try { focusRequesterMap[id]?.requestFocus() } catch (_: Exception) {}
        }
    }

    fun applyResult(result: SplitResult?) {
        if (result == null) return
        onBlocksChanged(result.newBlocks)
        // 새 블록의 id로 포커스 예약
        val targetBlock = result.newBlocks.getOrNull(result.focusBlockIndex)
        if (targetBlock != null) {
            focusRequesterMap.getOrPut(targetBlock.id) { FocusRequester() }
            pendingFocusBlockId = targetBlock.id
            focusRequestCounter++
        }
    }

    @Composable
    fun BlockWithNav(index: Int, block: EditorBlock) {
        val fr = focusRequesterMap[block.id] ?: remember { FocusRequester() }

        // LazyColumn이 아이템 recomposition을 skip해도 콜백이 최신 blocks/index를 참조하도록 보장
        val currentBlocks by rememberUpdatedState(blocks)
        val currentIndex by rememberUpdatedState(index)

        val nav = BlockNavigation(
            onMoveToPrevious = {
                if (currentIndex > 0) {
                    pendingFocusBlockId = currentBlocks[currentIndex - 1].id
                    focusRequestCounter++
                } else {
                    onEscapeToPrevious()
                }
            },
            onMoveToNext = {
                if (currentIndex < currentBlocks.lastIndex) {
                    pendingFocusBlockId = currentBlocks[currentIndex + 1].id
                    focusRequestCounter++
                } else {
                    onEscapeToNext()
                }
            },
            onMoveLeft = {
                if (currentIndex > 0) {
                    pendingFocusBlockId = currentBlocks[currentIndex - 1].id
                    focusRequestCounter++
                } else {
                    onEscapeLeft()
                }
            },
            onMergeWithPrevious = {
                applyResult(BlockOperations.mergeWithPrevious(currentBlocks, currentIndex))
            },
            onSplitBlock = {
                applyResult(BlockOperations.trySplitTextBlock(currentBlocks, currentIndex))
            },
            onSplitByEmptyLine = {
                applyResult(BlockOperations.trySplitByEmptyLine(currentBlocks, currentIndex))
            },
            onReparse = {
                applyResult(BlockOperations.tryReparse(currentBlocks, currentIndex))
            },
        )

        BlockItem(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = fr,
            navigation = nav,
            onBlocksChanged = onBlocksChanged,
            allBlocks = blocks,
            blockIndex = index,
        )
    }

    if (isNested) {
        Column(modifier = modifier) {
            for ((index, block) in blocks.withIndex()) {
                key(block.id) {
                    BlockWithNav(index, block)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    } else {
        LazyColumn(modifier = modifier) {
            itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
                BlockWithNav(index, block)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BlockItem(
    block: EditorBlock,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    focusRequester: FocusRequester,
    navigation: BlockNavigation,
    onBlocksChanged: (List<EditorBlock>) -> Unit,
    allBlocks: List<EditorBlock>,
    blockIndex: Int,
) {
    // LazyColumn이 아이템 recomposition을 skip해도 클로저가 최신 값을 참조하도록 보장
    val latestAllBlocks by rememberUpdatedState(allBlocks)
    val latestBlockIndex by rememberUpdatedState(blockIndex)

    when (block) {
        is EditorBlock.Text -> TextBlockEditor(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = focusRequester,
            navigation = navigation,
        )
        is EditorBlock.Callout -> CalloutBlockEditor(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = focusRequester,
            navigation = navigation,
            onBlocksChanged = { newBodyBlocks ->
                val currentBlocks = latestAllBlocks
                val idx = latestBlockIndex
                val currentCallout = currentBlocks[idx] as? EditorBlock.Callout ?: return@CalloutBlockEditor
                val newBlocks = currentBlocks.toMutableList()
                newBlocks[idx] = currentCallout.copy(bodyBlocks = newBodyBlocks)
                onBlocksChanged(newBlocks)
            },
        )
        is EditorBlock.Code -> CodeBlockEditor(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = focusRequester,
            navigation = navigation,
        )
        is EditorBlock.Table -> TableBlockEditor(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = focusRequester,
            navigation = navigation,
            onBlockChanged = { newTable ->
                val currentBlocks = latestAllBlocks
                val idx = latestBlockIndex
                val newBlocks = currentBlocks.toMutableList()
                newBlocks[idx] = newTable
                onBlocksChanged(newBlocks)
            },
        )
        is EditorBlock.HorizontalRule -> {
            // HR은 TextBlock 인라인 렌더링으로 전환됨 — 이 분기는 도달하지 않음
            // sealed class 호환성을 위해 유지
        }
        is EditorBlock.Embed -> {
            TextBlockEditor(
                block = EditorBlock.Text(
                    textFieldState = TextFieldState(block.toMarkdown()),
                ),
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                focusRequester = focusRequester,
                navigation = navigation,
            )
        }
    }
}
