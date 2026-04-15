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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
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
 * 블록 간 이동 시 커서 위치 힌트.
 */
sealed class CursorHint {
    /** 텍스트 맨 처음 (offset 0) */
    data object Start : CursorHint()
    /** 텍스트 맨 마지막 (offset = text.length) */
    data object End : CursorHint()
    /** 이전 커서의 x 좌표를 유지하여 대상 줄의 같은 위치로 이동 */
    data class AtX(val x: Float, val lastLine: Boolean) : CursorHint()
}

/**
 * 블록 간 내비게이션 및 분할/병합 콜백.
 */
data class BlockNavigation(
    val onMoveToPrevious: () -> Unit = {},
    val onMoveToNext: () -> Unit = {},
    /** x 좌표 힌트 포함 이전 블록 이동 (Text→Text) */
    val onMoveToPreviousWithX: (cursorX: Float) -> Unit = {},
    /** x 좌표 힌트 포함 다음 블록 이동 (Text→Text) */
    val onMoveToNextWithX: (cursorX: Float) -> Unit = {},
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
    lastBlockFocusRequester: FocusRequester? = null,
) {
    // LazyColumn 스크롤 상태 (화면 밖 블록에 포커스 시 스크롤 필요)
    val lazyListState = rememberLazyListState()

    // 블록 id → FocusRequester 맵 (블록이 추가/삭제되어도 기존 블록의 requester 유지)
    val focusRequesterMap = remember { mutableMapOf<String, FocusRequester>() }
    // 블록 리스트 변경 시 불필요한 requester 정리
    val currentIds = blocks.map { it.id }.toSet()
    focusRequesterMap.keys.retainAll(currentIds)
    for (block in blocks) {
        focusRequesterMap.getOrPut(block.id) { FocusRequester() }
    }
    // 외부에서 첫/마지막 블록의 FocusRequester를 지정한 경우 (Callout body 등)
    if (firstBlockFocusRequester != null && blocks.isNotEmpty()) {
        focusRequesterMap[blocks.first().id] = firstBlockFocusRequester
    }
    if (lastBlockFocusRequester != null && blocks.isNotEmpty()) {
        focusRequesterMap[blocks.last().id] = lastBlockFocusRequester
    }

    // 포커스 지연 요청: 블록 분할/병합/이동 후 대상 블록에 포커스
    // pendingFocusBlockId를 LaunchedEffect key로 사용하면 null 설정 시 effect가 취소되므로,
    // 별도 카운터를 key로 사용한다.
    var pendingFocusBlockId by remember { mutableStateOf<String?>(null) }
    var pendingCursorHint by remember { mutableStateOf<CursorHint?>(null) }
    var focusRequestCounter by remember { mutableStateOf(0) }
    LaunchedEffect(focusRequestCounter) {
        val id = pendingFocusBlockId ?: return@LaunchedEffect
        // 대상 블록이 화면 밖이면 한 줄 정도만 부드럽게 스크롤
        if (!isNested) {
            val targetIndex = blocks.indexOfFirst { it.id == id }
            if (targetIndex >= 0) {
                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                val visibleIndices = visibleItems.map { it.index }.toSet()
                if (targetIndex !in visibleIndices) {
                    // 대상이 위쪽이면 위로, 아래쪽이면 아래로 한 줄 분량 스크롤
                    val firstVisible = visibleItems.firstOrNull()?.index ?: 0
                    val scrollAmount = if (targetIndex < firstVisible) -80f else 80f
                    lazyListState.animateScrollBy(scrollAmount)
                    // 스크롤 후에도 안 보이면 직접 이동
                    kotlinx.coroutines.delay(50.milliseconds)
                    val stillVisible = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                    if (targetIndex !in stillVisible) {
                        lazyListState.animateScrollToItem(targetIndex)
                    }
                }
            }
        }
        kotlinx.coroutines.delay(50.milliseconds)
        try {
            focusRequesterMap[id]?.requestFocus()
        } catch (_: IllegalStateException) {
            kotlinx.coroutines.delay(100.milliseconds)
            try { focusRequesterMap[id]?.requestFocus() } catch (_: Exception) {}
        }
        // 포커스 후 커서 위치 설정
        val hint = pendingCursorHint
        val targetBlock = blocks.find { it.id == id }
        if (hint != null) {
            kotlinx.coroutines.delay(10.milliseconds)
            // AtX 힌트: 대상이 Text가 아니면 Start/End로 변환
            val effectiveHint = if (hint is CursorHint.AtX && targetBlock !is EditorBlock.Text) {
                if (hint.lastLine) CursorHint.End else CursorHint.Start
            } else hint

            if (effectiveHint is CursorHint.Start || effectiveHint is CursorHint.End) {
                val state = when (targetBlock) {
                    is EditorBlock.Text -> targetBlock.textFieldState
                    is EditorBlock.Code -> targetBlock.codeState
                    else -> null
                }
                if (state != null) {
                    val offset = when (effectiveHint) {
                        is CursorHint.Start -> 0
                        is CursorHint.End -> state.text.length
                        else -> state.text.length
                    }
                    state.edit {
                        selection = androidx.compose.ui.text.TextRange(offset)
                    }
                }
            }
            // AtX + Text 대상은 TextBlockEditor 내부에서 정밀 처리
        }
        pendingCursorHint = null
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
                    // Block→Text ↑: 맨 마지막으로
                    pendingCursorHint = CursorHint.End
                    focusRequestCounter++
                } else {
                    onEscapeToPrevious()
                }
            },
            onMoveToNext = {
                if (currentIndex < currentBlocks.lastIndex) {
                    pendingFocusBlockId = currentBlocks[currentIndex + 1].id
                    // Block→Text ↓: 맨 처음으로
                    pendingCursorHint = CursorHint.Start
                    focusRequestCounter++
                } else {
                    onEscapeToNext()
                }
            },
            onMoveToPreviousWithX = { cursorX ->
                if (currentIndex > 0) {
                    pendingFocusBlockId = currentBlocks[currentIndex - 1].id
                    // Text→Text ↑: 이전 블록 마지막 줄의 같은 x 위치
                    pendingCursorHint = CursorHint.AtX(cursorX, lastLine = true)
                    focusRequestCounter++
                } else {
                    onEscapeToPrevious()
                }
            },
            onMoveToNextWithX = { cursorX ->
                if (currentIndex < currentBlocks.lastIndex) {
                    pendingFocusBlockId = currentBlocks[currentIndex + 1].id
                    // Text→Text ↓: 다음 블록 첫 줄의 같은 x 위치
                    pendingCursorHint = CursorHint.AtX(cursorX, lastLine = false)
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
            cursorHint = if (pendingFocusBlockId == block.id) pendingCursorHint else null,
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
        LazyColumn(modifier = modifier, state = lazyListState) {
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
    cursorHint: CursorHint? = null,
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
            cursorHint = cursorHint,
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
