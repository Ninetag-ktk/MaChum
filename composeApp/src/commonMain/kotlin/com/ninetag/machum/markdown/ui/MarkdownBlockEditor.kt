package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.BlockOperations
import com.ninetag.machum.markdown.state.DissolveResult
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.SplitResult
import com.ninetag.machum.markdown.state.normalize
import com.ninetag.machum.markdown.ui.block.CalloutBlockEditor
import com.ninetag.machum.markdown.ui.block.CodeBlockEditor
import com.ninetag.machum.markdown.ui.block.TableBlockEditor
import com.ninetag.machum.markdown.ui.selection.extendSelectionToNext
import com.ninetag.machum.markdown.ui.selection.extendSelectionToPrevious
import com.ninetag.machum.markdown.ui.selection.isBlockInSelection
import com.ninetag.machum.markdown.ui.selection.selectBlockAsAtomic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.snapshotFlow
import com.ninetag.machum.markdown.state.RawOrigin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
    /** 정확한 offset 으로 커서 이동 (dissolve 결과 적용 시 사용) */
    data class AtOffset(val offset: Int) : CursorHint()
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
    /** focus-out 트리거 reparse: 블록 교체만 하고 새 블록으로 focus 를 이동시키지 않는다.
     *  사용자가 이미 다른 블록으로 포커스를 옮긴 상태이므로 그 포커스를 보존해야 함 (dissolve v3) */
    val onReparseSilent: () -> Unit = {},
    /** Callout title 위치 0 Backspace 등 자기 자신을 dissolve 하는 트리거. dissolve 정책 v3 (CLAUDE_sub.md 섹션 10) */
    val onDissolveSelf: () -> Unit = {},
    /** raw 블록(rawMode=true) 의 텍스트가 빈 상태가 되면 즉시 rawMode 해제.
     *  block.id/textFieldState 유지하므로 cursor/focus 보존 (transient 상태 정리) */
    val onClearRawMode: () -> Unit = {},
    /**
     * Shift+↑ 등으로 현재 블록의 첫 위치에서 위쪽으로 selection 확장 요청.
     * Phase 1 Step B: 현재 블록을 atomic 으로 selection 에 포함 + anchor 가 없으면 anchor=현재 끝,
     * 그 다음 focus 를 이전 블록으로 확장. SELECTION.md 참조
     */
    val onExtendSelectionToPrevious: () -> Unit = {},
    /** Shift+↓ 등으로 현재 블록의 끝 위치에서 아래쪽으로 selection 확장 요청 */
    val onExtendSelectionToNext: () -> Unit = {},
    /**
     * Callout 의 title/body 경계에서 외부로 "박스 탈출" 시 호출.
     * 현재 블록 (Callout) 자체만 atomic selection 으로 documentSelection 갱신.
     * 외부 다른 블록은 selection 에 포함 X. SELECTION.md 2.4 옵션 C 정책.
     */
    val onSelectSelfAsAtomic: () -> Unit = {},
)

/**
 * 블록 리스트를 렌더링하는 에디터 Composable.
 */
@Suppress("RememberInComposition")
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
    /** 마지막 블록이 bottomEntryFR을 등록했을 때 부모에게 전파하는 콜백 (중첩 Callout 체인) */
    onLastBlockBottomEntryRegistered: (FocusRequester?) -> Unit = {},
    /** tryReparse 시 생성을 금지할 Callout 타입 (DL 중첩 방지 등) */
    excludeCalloutTypes: Set<String> = emptySet(),
    /**
     * 이 컨테이너 안의 TextBlock 에서 빈 마지막 줄 + Enter → 다음 블록으로 탈출 활성화.
     * Callout 의 body 호출에서만 true. 외부(최상위) 호출에서는 false (default).
     * CLAUDE_sub.md #20 정책 v2.
     */
    enableEnterEscape: Boolean = false,
    /**
     * Cross-block selection 상태. 최상위에서 호이스팅한 [DocumentSelection] 을 공유. Phase 1.
     * null 이면 selection 기능 비활성 (재귀 호출에서 부모와 같은 인스턴스를 항상 전달).
     */
    documentSelection: androidx.compose.runtime.MutableState<DocumentSelection>? = null,
    /**
     * 이 컨테이너의 path — 최상위는 empty, Callout body 호출 시 ["calloutId"] 같이 누적.
     * SelectionEndpoint 생성 시 사용. SELECTION.md 참조.
     */
    containerPath: List<String> = emptyList(),
    /**
     * 컨테이너 (body) 의 첫 블록에서 Shift+↑ → 외부 컨테이너로 escape.
     * Callout body 가 호출 시 `{ navigation.onSelectSelfAsAtomic() }` 으로 연결되어 부모 Callout 자체가
     * atomic 으로 selected 됨. B-2c 의 "박스 탈출" 메커니즘.
     */
    onEscapeSelectionToPrevious: () -> Unit = {},
    /** 컨테이너 (body) 의 마지막 블록에서 Shift+↓ → 외부 컨테이너로 escape. */
    onEscapeSelectionToNext: () -> Unit = {},
) {
    // LazyColumn 스크롤 상태 (화면 밖 블록에 포커스 시 스크롤 필요)
    val lazyListState = rememberLazyListState()

    // 블록 id → FocusRequester 맵 (↓ 진입 / 기본)
    val focusRequesterMap = remember { mutableMapOf<String, FocusRequester>() }
    // 블록 id → FocusRequester 맵 (↑ 진입용, Callout만 등록. 미등록 시 focusRequesterMap fallback)
    val bottomEntryFRMap = remember { mutableMapOf<String, FocusRequester>() }

    // 블록 리스트 변경 시 불필요한 requester 정리
    val currentIds = blocks.map { it.id }.toSet()
    focusRequesterMap.keys.retainAll(currentIds)
    bottomEntryFRMap.keys.retainAll(currentIds)
    for (block in blocks) {
        focusRequesterMap.getOrPut(block.id) { FocusRequester() }
    }
    // 외부에서 첫/마지막 블록의 FocusRequester를 지정한 경우 (Callout body 등)
    // last를 먼저 등록하고 first를 나중에 등록: first == last (1블록)일 때 first가 우선
    if (lastBlockFocusRequester != null && blocks.isNotEmpty()) {
        focusRequesterMap[blocks.last().id] = lastBlockFocusRequester
    }
    if (firstBlockFocusRequester != null && blocks.isNotEmpty()) {
        focusRequesterMap[blocks.first().id] = firstBlockFocusRequester
    }

    // 포커스 지연 요청: 블록 분할/병합/이동 후 대상 블록에 포커스
    var pendingFocusBlockId by remember { mutableStateOf<String?>(null) }
    var pendingCursorHint by remember { mutableStateOf<CursorHint?>(null) }
    var pendingUseBottomEntry by remember { mutableStateOf(false) }
    var focusRequestCounter by remember { mutableStateOf(0) }
    LaunchedEffect(focusRequestCounter) {
        val id = pendingFocusBlockId ?: return@LaunchedEffect
        // 대상 블록이 화면 밖이면 스크롤
        if (!isNested) {
            val targetIndex = blocks.indexOfFirst { it.id == id }
            if (targetIndex >= 0) {
                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                val visibleIndices = visibleItems.map { it.index }.toSet()
                if (targetIndex !in visibleIndices) {
                    val firstVisible = visibleItems.firstOrNull()?.index ?: 0
                    val scrollAmount = if (targetIndex < firstVisible) -80f else 80f
                    lazyListState.animateScrollBy(scrollAmount)
                    kotlinx.coroutines.delay(50.milliseconds)
                    val stillVisible = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                    if (targetIndex !in stillVisible) {
                        lazyListState.animateScrollToItem(targetIndex)
                    }
                }
            }
        }
        // ↑ 진입 시 bottomEntryFRMap 우선, 없으면 focusRequesterMap fallback
        val targetFR = if (pendingUseBottomEntry) {
            bottomEntryFRMap[id] ?: focusRequesterMap[id]
        } else {
            focusRequesterMap[id]
        }
        kotlinx.coroutines.delay(50.milliseconds)
        try {
            targetFR?.requestFocus()
        } catch (_: IllegalStateException) {
            kotlinx.coroutines.delay(100.milliseconds)
            try { targetFR?.requestFocus() } catch (_: Exception) {}
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
                // bottomEntry로 포커스한 경우 → Callout body의 TextBlock에 도달했으므로 커서 설정 불필요
                // (focusRequesterMap의 Text/Code에만 적용)
                if (!pendingUseBottomEntry) {
                    val state = when (targetBlock) {
                        is EditorBlock.Text -> targetBlock.textFieldState
                        is EditorBlock.Code -> targetBlock.codeState
                        else -> null
                    }
                    if (state != null) {
                        val offset = when (effectiveHint) {
                            is CursorHint.Start -> 0
                            is CursorHint.End -> state.text.length
                        }
                        state.edit {
                            selection = androidx.compose.ui.text.TextRange(offset)
                        }
                    }
                }
            }
            // bottomEntry + Callout + End → body 가장 깊은 마지막 Text 의 cursor 를 End 로 강제 설정.
            // (FocusRequester 만 호출하면 그 블록의 이전 selection 이 복원되어 사용자 의도와 어긋남)
            if (effectiveHint is CursorHint.End && pendingUseBottomEntry && targetBlock is EditorBlock.Callout) {
                val lastText = findDeepestLastText(targetBlock.bodyBlocks)
                if (lastText != null) {
                    val len = lastText.textFieldState.text.length
                    lastText.textFieldState.edit {
                        selection = androidx.compose.ui.text.TextRange(len)
                    }
                }
            }
            if (effectiveHint is CursorHint.AtOffset && targetBlock is EditorBlock.Text) {
                val state = targetBlock.textFieldState
                val offset = effectiveHint.offset.coerceIn(0, state.text.length)
                state.edit {
                    selection = androidx.compose.ui.text.TextRange(offset)
                }
            }
            // AtX + Text 대상은 TextBlockEditor 내부에서 정밀 처리
        }
        pendingCursorHint = null
        pendingUseBottomEntry = false
    }

    fun applyResult(result: SplitResult?, requestFocus: Boolean = true) {
        if (result == null) return
        onBlocksChanged(result.newBlocks)
        if (!requestFocus) return
        // 새 블록의 id로 포커스 예약
        val targetBlock = result.newBlocks.getOrNull(result.focusBlockIndex)
        if (targetBlock != null) {
            focusRequesterMap.getOrPut(targetBlock.id) { FocusRequester() }
            pendingFocusBlockId = targetBlock.id
            focusRequestCounter++
        }
    }

    // Cross-block selection 이 Multi 로 갱신될 때마다 focus 를 selection 의 focus endpoint 블록으로 이동.
    // Shift+↑/↓ 확장 시 cursor 가 selection 의 끝으로 따라가도록. focus 이동 시 onFocusChanged 에서
    // resetDocumentSelectionOnFocus 가 발동하지만 endpoint 블록 매칭으로 reset 안 됨 (selection 보존).
    val multiSelection = documentSelection?.value as? DocumentSelection.Multi
    if (multiSelection != null && multiSelection.focus.containerPath == containerPath) {
        val focusTargetId = multiSelection.focus.blockId
        LaunchedEffect(focusTargetId) {
            // 화면 밖 focus endpoint 를 뷰포트로 스크롤 → 노드 compose → FocusRequester 초기화 →
            // requestFocus 성공. root LazyColumn 한정 (nested Callout body 는 Column 이라 recycle 없음).
            // 누적 확장은 한 칸씩 빠르게 이어지므로, 고정 nudge + 대기 대신 **필요한 delta 만 한 번에**
            // 스크롤해 지연을 없앤다.
            if (!isNested) {
                val targetIndex = blocks.indexOfFirst { it.id == focusTargetId }
                if (targetIndex >= 0) {
                    val info = lazyListState.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                    if (item == null) {
                        // 화면에 아예 없음 (멀리 점프) → 바로 해당 아이템으로
                        lazyListState.animateScrollToItem(targetIndex)
                    } else {
                        // 부분만 보임 → 가장자리로 끌어오는 만큼만 (한 번에)
                        val delta = when {
                            item.offset < info.viewportStartOffset ->
                                (item.offset - info.viewportStartOffset).toFloat()
                            item.offset + item.size > info.viewportEndOffset ->
                                (item.offset + item.size - info.viewportEndOffset).toFloat()
                            else -> 0f
                        }
                        if (delta != 0f) lazyListState.animateScrollBy(delta)
                    }
                }
            }
            val fr = focusRequesterMap[focusTargetId] ?: return@LaunchedEffect
            try { fr.requestFocus() } catch (_: Exception) {}
        }
    }

    /**
     * dissolve 결과 적용. 새 raw TextBlock 으로 포커스 + 커서 위치 = raw 끝.
     * (CLAUDE_sub.md 섹션 10 dissolve 정책)
     */
    fun applyDissolveResult(result: DissolveResult?) {
        if (result == null) return
        onBlocksChanged(result.newBlocks)
        focusRequesterMap.getOrPut(result.targetBlockId) { FocusRequester() }
        pendingFocusBlockId = result.targetBlockId
        pendingCursorHint = CursorHint.AtOffset(result.cursorOffset)
        pendingUseBottomEntry = false
        focusRequestCounter++
    }

    // Cross-block selection 시각화 (Phase 1) — 정규화는 한 번만 계산 후 isBlockInSelection 헬퍼에 전달.
    // 재귀 Callout body 안 selection 은 Step B 의 path 전파 완료 후 자연스럽게 동작.
    val normalizedSelection = (documentSelection?.value as? DocumentSelection.Multi)?.normalize(blocks)

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
                    pendingCursorHint = CursorHint.End
                    pendingUseBottomEntry = true  // ↑ 진입: bottomEntryFRMap 우선
                    focusRequestCounter++
                } else {
                    onEscapeToPrevious()
                }
            },
            onMoveToNext = {
                if (currentIndex < currentBlocks.lastIndex) {
                    pendingFocusBlockId = currentBlocks[currentIndex + 1].id
                    pendingCursorHint = CursorHint.Start
                    pendingUseBottomEntry = false  // ↓ 진입: focusRequesterMap
                    focusRequestCounter++
                } else {
                    onEscapeToNext()
                }
            },
            onMoveToPreviousWithX = { cursorX ->
                if (currentIndex > 0) {
                    pendingFocusBlockId = currentBlocks[currentIndex - 1].id
                    pendingCursorHint = CursorHint.AtX(cursorX, lastLine = true)
                    pendingUseBottomEntry = true
                    focusRequestCounter++
                } else {
                    onEscapeToPrevious()
                }
            },
            onMoveToNextWithX = { cursorX ->
                if (currentIndex < currentBlocks.lastIndex) {
                    pendingFocusBlockId = currentBlocks[currentIndex + 1].id
                    pendingCursorHint = CursorHint.AtX(cursorX, lastLine = false)
                    pendingUseBottomEntry = false
                    focusRequestCounter++
                } else {
                    onEscapeToNext()
                }
            },
            onMoveLeft = {
                if (currentIndex > 0) {
                    pendingFocusBlockId = currentBlocks[currentIndex - 1].id
                    // ↑ 와 동일한 진입 의미: 이전 블록의 마지막 위치(body 끝, 없으면 title 끝)
                    pendingCursorHint = CursorHint.End
                    pendingUseBottomEntry = true
                    focusRequestCounter++
                } else {
                    onEscapeLeft()
                }
            },
            onMergeWithPrevious = {
                val merged = BlockOperations.mergeWithPrevious(currentBlocks, currentIndex)
                if (merged != null) {
                    applyResult(merged)
                } else {
                    // 기존 merge 룰이 적용되지 않을 때 직전이 특수 블록(Code/Callout/Table)이면
                    // dissolve 정책으로 라우팅 (CLAUDE_sub.md 섹션 10)
                    val prev = currentBlocks.getOrNull(currentIndex - 1)
                    if (prev != null && prev !is EditorBlock.Text && prev !is EditorBlock.HorizontalRule) {
                        applyDissolveResult(BlockOperations.dissolveSpecial(currentBlocks, currentIndex - 1))
                    }
                }
            },
            onSplitBlock = {
                applyResult(BlockOperations.trySplitTextBlock(currentBlocks, currentIndex))
            },
            onSplitByEmptyLine = {
                applyResult(BlockOperations.trySplitByEmptyLine(currentBlocks, currentIndex))
            },
            onReparse = {
                applyResult(BlockOperations.tryReparse(currentBlocks, currentIndex, excludeCalloutTypes))
            },
            onReparseSilent = {
                // focus-out 트리거 reparse — 블록 교체만, focus 는 사용자가 옮긴 곳 그대로 유지
                applyResult(
                    BlockOperations.tryReparse(currentBlocks, currentIndex, excludeCalloutTypes),
                    requestFocus = false,
                )
            },
            onDissolveSelf = {
                // 모든 특수 블록(Code/Callout/Table/Embed) 자기 자신 dissolve 통합 라우팅
                applyDissolveResult(BlockOperations.dissolveSpecial(currentBlocks, currentIndex))
            },
            onClearRawMode = {
                val cur = currentBlocks.getOrNull(currentIndex) as? EditorBlock.Text
                if (cur != null && cur.rawMode) {
                    val newBlocks = currentBlocks.toMutableList()
                    // id/textFieldState 유지, 플래그만 해제 → cursor/focus 보존
                    newBlocks[currentIndex] = cur.copy(rawMode = false, rawOrigin = null)
                    onBlocksChanged(newBlocks)
                }
            },
            onExtendSelectionToPrevious = {
                extendSelectionToPrevious(
                    currentBlock = currentBlocks[currentIndex],
                    currentIndex = currentIndex,
                    blocksInContainer = currentBlocks,
                    containerPath = containerPath,
                    documentSelection = documentSelection,
                    onEscapeToParent = onEscapeSelectionToPrevious,
                )
            },
            onExtendSelectionToNext = {
                extendSelectionToNext(
                    currentBlock = currentBlocks[currentIndex],
                    currentIndex = currentIndex,
                    blocksInContainer = currentBlocks,
                    containerPath = containerPath,
                    documentSelection = documentSelection,
                    onEscapeToParent = onEscapeSelectionToNext,
                )
            },
            onSelectSelfAsAtomic = {
                selectBlockAsAtomic(
                    block = currentBlocks[currentIndex],
                    containerPath = containerPath,
                    documentSelection = documentSelection,
                )
            },
        )

        val selected = isBlockInSelection(
            blockIndex = index,
            blocksInContainer = blocks,
            containerPath = containerPath,
            normalizedSelection = normalizedSelection,
        )
        val itemBackground = if (selected) {
            Modifier.background(styleConfig.selectionAccent)
        } else Modifier

        Box(modifier = itemBackground) {
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
            enableEnterEscape = enableEnterEscape,
            documentSelection = documentSelection,
            containerPath = containerPath,
            onRegisterBottomEntryFR = { frOrNull ->
                if (frOrNull != null) {
                    bottomEntryFRMap[block.id] = frOrNull
                } else {
                    bottomEntryFRMap.remove(block.id)
                }
                // 마지막 블록의 bottomFR이 변경되면 부모로 전파 (중첩 Callout 체인)
                if (currentIndex == currentBlocks.lastIndex) {
                    onLastBlockBottomEntryRegistered(frOrNull)
                }
            },
        )
        }  // Box (selection 배경 wrapping)
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
    enableEnterEscape: Boolean = false,
    /** Callout 분기에서 body 재귀 호출에 전파 — Step B-2c body 안 cross-selection 활성 */
    documentSelection: androidx.compose.runtime.MutableState<DocumentSelection>? = null,
    containerPath: List<String> = emptyList(),
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit = {},
) {
    // LazyColumn이 아이템 recomposition을 skip해도 클로저가 최신 값을 참조하도록 보장
    val latestAllBlocks by rememberUpdatedState(allBlocks)
    val latestBlockIndex by rememberUpdatedState(blockIndex)

    // Block 유형(Code/Callout/Table) 의 state-empty 자동 격하는 두지 않는다 (CLAUDE_sub.md 섹션 10 정책 정정).
    // 자동 격하의 본래 의도는 raw 블록의 마커 깨짐을 일반 텍스트로 정리하는 것이며 이는 tryReparse 가 처리.
    // 박스 UI 자체의 state-empty 트리거는 사용자가 title 잠깐 비운 채 다시 입력하려는 단순 편집에서도
    // 박스가 사라지는 부작용이 있어 제거됨.

    when (block) {
        is EditorBlock.Text -> TextBlockEditor(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = focusRequester,
            navigation = navigation,
            cursorHint = cursorHint,
            escapeOnEmptyEnter = enableEnterEscape,
        )
        is EditorBlock.Callout -> CalloutBlockEditor(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = focusRequester,
            navigation = navigation,
            onRegisterBottomEntryFR = onRegisterBottomEntryFR,
            onBlocksChanged = { newBodyBlocks ->
                val currentBlocks = latestAllBlocks
                val idx = latestBlockIndex
                val currentCallout = currentBlocks[idx] as? EditorBlock.Callout ?: return@CalloutBlockEditor
                val newBlocks = currentBlocks.toMutableList()
                newBlocks[idx] = currentCallout.copy(bodyBlocks = newBodyBlocks)
                onBlocksChanged(newBlocks)
            },
            documentSelection = documentSelection,
            containerPath = containerPath,
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
            onRegisterBottomEntryFR = onRegisterBottomEntryFR,
        )
        is EditorBlock.HorizontalRule -> {
            // HR은 TextBlock 인라인 렌더링으로 전환됨 — 이 분기는 도달하지 않음
            // sealed class 호환성을 위해 유지
        }
        is EditorBlock.Embed -> {
            // Embed 는 자체 박스 UI 가 없어 raw markdown 을 BasicTextField 로 표시.
            // 매번 새 TextFieldState 를 만들면 사용자 입력이 부모로 전파되지 않고 다음
            // recomposition 때 복원되는 버그가 있어, tempState 를 remember 로 보존하고
            // 사용자 입력 감지 시 raw TextBlock(rawMode=true, rawOrigin=EMBED) 으로 promotion.
            // (CLAUDE_sub.md 섹션 10 자동 격하/해제 정책 4)
            val original = remember(block.id) { block.toMarkdown() }
            val tempState = remember(block.id) { TextFieldState(original) }
            LaunchedEffect(block.id) {
                // 첫 변경 1회만 promotion. collectLatest 로 매 입력마다 호출하면 onBlocksChanged 가
                // 반복 발동되어 컴포지션 불안정 + cursor 깜빡임 발생.
                snapshotFlow { tempState.text.toString() }
                    .filter { it != original }
                    .first()
                val currentBlocks = latestAllBlocks
                val idx = latestBlockIndex
                // 같은 id + 같은 textFieldState 로 promotion → cursor/입력 그대로 보존
                val promoted = EditorBlock.Text(
                    id = block.id,
                    textFieldState = tempState,
                    rawMode = true,
                    rawOrigin = RawOrigin.EMBED,
                )
                val newBlocks = currentBlocks.toMutableList()
                newBlocks[idx] = promoted
                onBlocksChanged(newBlocks)
                // promotion 후 BlockItem 의 when 분기가 Embed → Text 로 전환되며 BasicTextField 재생성 →
                // 시스템 focus 가 끊겨 cursor 가 화면에서 사라짐. 같은 외부 focusRequester 를 재요청.
                kotlinx.coroutines.delay(50.milliseconds)
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
            TextBlockEditor(
                block = EditorBlock.Text(id = block.id, textFieldState = tempState),
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                focusRequester = focusRequester,
                navigation = navigation,
            )
        }
    }
}

/**
 * Callout body 의 가장 깊은 마지막 [EditorBlock.Text] 를 재귀적으로 찾는다.
 * 중첩 Callout 의 body 까지 들어가서 검색. 빈 body 또는 Text 가 없으면 null.
 *
 * dissolve 정책 v3: bottomEntry 진입 시 cursor 를 강제로 End 로 이동하기 위해 사용.
 */
private fun findDeepestLastText(blocks: List<EditorBlock>): EditorBlock.Text? {
    for (b in blocks.reversed()) {
        when (b) {
            is EditorBlock.Text -> return b
            is EditorBlock.Callout -> findDeepestLastText(b.bodyBlocks)?.let { return it }
            else -> continue
        }
    }
    return null
}
