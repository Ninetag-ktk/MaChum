package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.service.MarkdownEditorDebugOptions
import com.ninetag.machum.markdown.state.CursorHint
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.EditorFocusCoordinator
import com.ninetag.machum.markdown.state.EditorFocusIntent
import com.ninetag.machum.markdown.state.EditorFocusRequest
import com.ninetag.machum.markdown.state.EditorMutation
import com.ninetag.machum.markdown.state.EditorMutationDispatcher
import com.ninetag.machum.markdown.state.insertDlCallout
import com.ninetag.machum.markdown.state.MarkdownRenderContext
import com.ninetag.machum.markdown.state.standaloneMarkdownEmbedTarget
import com.ninetag.machum.markdown.state.markdownEmbedSourceAtSelection
import com.ninetag.machum.markdown.ui.diagnostics.MarkdownImeDiagnostics
import com.ninetag.machum.markdown.state.RawOrigin
import com.ninetag.machum.markdown.state.SelectionEndpoint
import com.ninetag.machum.markdown.state.normalizeForContainer
import com.ninetag.machum.markdown.ui.block.CalloutBlockEditor
import com.ninetag.machum.markdown.ui.block.CodeBlockEditor
import com.ninetag.machum.markdown.ui.block.MarkdownEmbedPreviewSurface
import com.ninetag.machum.markdown.ui.block.TableBlockEditor
import com.ninetag.machum.markdown.ui.selection.extendSelectionToNext
import com.ninetag.machum.markdown.ui.selection.extendSelectionToPrevious
import com.ninetag.machum.markdown.ui.selection.isBlockInSelection
import com.ninetag.machum.markdown.ui.selection.LocalDocumentInputFocusRequest
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelectionPointerRegistry
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelectionPointerActive
import com.ninetag.machum.markdown.ui.selection.selectionPointerTargetKey
import com.ninetag.machum.markdown.ui.selection.selectBlockAsAtomic
import com.ninetag.machum.markdown.ui.diagnostics.TrackEditorRecomposition
import com.ninetag.machum.markdown.ui.diagnostics.TrackEditorSelectionRecomposition

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private data class PendingDocumentFocus(
    val documentRequestId: Long,
    val coordinatorRequestId: Long,
)

internal data class EditorNavigationRequest(
    val id: Long,
    val blockId: String,
    val sourceOffset: Int,
)

/** Shared by the root editor so an explicit history reveal requires full line visibility. */
internal val LocalEditorFullBringIntoView = compositionLocalOf<MutableState<Boolean>?> { null }

private class EditorBringIntoViewSpec(
    private val keepPartiallyVisibleTarget: () -> Boolean,
    private val suppressScroll: () -> Boolean,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (suppressScroll()) {
            0f
        } else {
            editorBringIntoViewDistance(
                offset = offset,
                size = size,
                containerSize = containerSize,
                keepPartiallyVisible = keepPartiallyVisibleTarget(),
            )
        }
}

internal fun editorBringIntoViewDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    keepPartiallyVisible: Boolean = false,
): Float {
    if (size <= 0f || containerSize <= 0f) return 0f
    val trailingEdge = offset + size
    if (keepPartiallyVisible && trailingEdge > 0f && offset < containerSize) return 0f
    return when {
        offset >= 0f && trailingEdge <= containerSize -> 0f
        offset < 0f && trailingEdge > containerSize -> 0f
        abs(offset) < abs(trailingEdge - containerSize) -> offset
        else -> trailingEdge - containerSize
    }
}

internal fun editorItemScrollDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
    preferEnd: Boolean = false,
): Float {
    val startDelta = itemOffset - viewportStart
    val endDelta = itemOffset + itemSize - viewportEnd
    val viewportSize = viewportEnd - viewportStart
    if (itemSize > viewportSize) {
        return if (preferEnd) {
            if (endDelta > 0) endDelta.toFloat() else 0f
        } else {
            if (startDelta < 0) startDelta.toFloat() else 0f
        }
    }
    val startClipped = startDelta < 0
    val endClipped = endDelta > 0
    return when {
        startClipped && endClipped -> if (preferEnd) endDelta.toFloat() else startDelta.toFloat()
        startClipped -> startDelta.toFloat()
        endClipped -> endDelta.toFloat()
        else -> 0f
    }
}

internal fun requiresTextLayoutAck(cursorHint: CursorHint?, targetBlock: EditorBlock?): Boolean =
    ((cursorHint is CursorHint.AtX ||
        cursorHint is CursorHint.AtOffset ||
        cursorHint is CursorHint.RestoredSelection ||
        cursorHint is CursorHint.Start ||
        cursorHint is CursorHint.End) &&
        targetBlock is EditorBlock.Text) ||
        (cursorHint is CursorHint.TableCell && targetBlock is EditorBlock.Table) ||
        (cursorHint is CursorHint.CalloutBodyEnd && targetBlock is EditorBlock.Callout)

/**
 * 블록 리스트를 렌더링하는 에디터 Composable.
 */
@Suppress("RememberInComposition")
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownBlockEditor(
    blocks: List<EditorBlock>,
    onBlocksChanged: (List<EditorBlock>) -> Unit,
    modifier: Modifier = Modifier,
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig(),
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
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
     * docs/markdown-editor.md의 Smart Enter 정책.
     */
    enableEnterEscape: Boolean = false,
    /**
     * Cross-block selection 상태. 최상위에서 호이스팅한 [DocumentSelection] 을 공유. Phase 1.
     * null 이면 selection 기능 비활성 (재귀 호출에서 부모와 같은 인스턴스를 항상 전달).
     */
    documentSelection: MutableState<DocumentSelection>? = null,
    /**
     * 이 컨테이너의 path — 최상위는 empty, Callout body 호출 시 ["calloutId"] 같이 누적.
     * SelectionEndpoint 생성 시 사용. docs/markdown-editor.md 참조.
     */
    containerPath: List<String> = emptyList(),
    /**
     * 컨테이너 (body) 의 첫 블록에서 Shift+↑ → 외부 컨테이너로 escape.
     * Callout body 가 호출 시 `{ navigation.selection.onSelectSelfAsAtomic() }` 으로 연결되어 부모 Callout 자체가
     * atomic 으로 selected 됨. B-2c 의 "박스 탈출" 메커니즘.
     */
    onEscapeSelectionToPrevious: () -> Unit = {},
    /** 컨테이너 (body) 의 마지막 블록에서 Shift+↓ → 외부 컨테이너로 escape. */
    onEscapeSelectionToNext: () -> Unit = {},
    /** 외부 문서 교체 시 이전 문서의 대기 중 포커스 요청을 폐기하는 수명 세대. */
    focusEpoch: Any = Unit,
    /** 최상위 문서가 Undo/Redo viewport를 복원할 때 공유하는 스크롤 상태. */
    rootLazyListState: LazyListState? = null,
    /** 링크 대상의 원문 위치를 포커스 변경 없이 viewport 상단에 맞추는 일회성 요청. */
    navigationRequest: EditorNavigationRequest? = null,
    onNavigationHandled: (Long) -> Unit = {},
    /** 히스토리 복원 레이아웃 동안 TextField의 자동 bring-into-view를 잠시 억제한다. */
    suppressBringIntoView: () -> Boolean = { false },
) {
    TrackEditorRecomposition(
        scope = "container",
        key = containerPath.lastOrNull() ?: "root",
    )
    TrackEditorSelectionRecomposition(
        documentSelection = documentSelection,
        key = containerPath.lastOrNull() ?: "root",
    )
    // LazyColumn 스크롤 상태 (화면 밖 블록에 포커스 시 스크롤 필요)
    val ownedLazyListState = rememberLazyListState()
    val lazyListState = rootLazyListState ?: ownedLazyListState
    var viewportTopInRoot by remember { mutableStateOf<Float?>(null) }
    var readyNavigationRequestId by remember { mutableStateOf<Long?>(null) }
    var positionedNavigationTarget by remember { mutableStateOf<Pair<Long, Float>?>(null) }
    val latestNavigationRequest by rememberUpdatedState(navigationRequest)
    val latestOnNavigationHandled by rememberUpdatedState(onNavigationHandled)
    val inheritedFullBringIntoView = LocalEditorFullBringIntoView.current
    val ownedFullBringIntoView = remember { mutableStateOf(false) }
    val fullBringIntoView = inheritedFullBringIntoView ?: ownedFullBringIntoView
    // BasicTextField may request bring-into-view after the pointer gesture has already ended.
    // Keep any target that already intersects the viewport stable regardless of request timing.
    val currentSuppressBringIntoView by rememberUpdatedState(suppressBringIntoView)
    val editorBringIntoViewSpec = remember {
        EditorBringIntoViewSpec(
            keepPartiallyVisibleTarget = { !fullBringIntoView.value },
            suppressScroll = { currentSuppressBringIntoView() },
        )
    }
    val showTrailingTextInput = shouldShowTrailingTextInput(blocks, isNested)
    val expandEmptyRootInput = shouldExpandEmptyRootInput(blocks, isNested)
    val trailingTextBlock = remember(blocks.lastOrNull()?.id, showTrailingTextInput) {
        if (showTrailingTextInput) {
            EditorBlock.Text(textFieldState = TextFieldState(""))
        } else {
            null
        }
    }
    val lastRealBlockId = blocks.lastOrNull()?.id
    val trailingInputHeightPx by remember(lazyListState, lastRealBlockId) {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val lastRealItem = layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == lastRealBlockId }
            if (lastRealItem == null) {
                0
            } else {
                remainingTrailingInputHeightPx(
                    viewportEndOffset = layoutInfo.viewportEndOffset,
                    lastRealItemEndOffset = lastRealItem.offset + lastRealItem.size,
                )
            }
        }
    }
    val trailingInputHeight = with(androidx.compose.ui.platform.LocalDensity.current) { trailingInputHeightPx.toDp() }
        .coerceAtLeast(24.dp)

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

    LaunchedEffect(navigationRequest?.id, currentIds) {
        val request = navigationRequest ?: return@LaunchedEffect
        readyNavigationRequestId = null
        positionedNavigationTarget = null
        val targetIndex = blocks.indexOfFirst { it.id == request.blockId }
        if (targetIndex < 0) {
            latestOnNavigationHandled(request.id)
            return@LaunchedEffect
        }
        lazyListState.scrollToItem(targetIndex)
        if (blocks[targetIndex] is EditorBlock.Text) {
            withFrameNanos { }
            readyNavigationRequestId = request.id
        } else {
            latestOnNavigationHandled(request.id)
        }
    }

    LaunchedEffect(positionedNavigationTarget, viewportTopInRoot, navigationRequest?.id) {
        val (requestId, targetTopInRoot) = positionedNavigationTarget ?: return@LaunchedEffect
        val viewportTop = viewportTopInRoot ?: return@LaunchedEffect
        if (latestNavigationRequest?.id != requestId) {
            positionedNavigationTarget = null
            return@LaunchedEffect
        }
        lazyListState.scrollBy(targetTopInRoot - viewportTop)
        if (latestNavigationRequest?.id == requestId) latestOnNavigationHandled(requestId)
        positionedNavigationTarget = null
    }

    // 외부에서 첫/마지막 블록의 FocusRequester를 지정한 경우 (Callout body 등)
    // last를 먼저 등록하고 first를 나중에 등록: first == last (1블록)일 때 first가 우선
    if (lastBlockFocusRequester != null && blocks.isNotEmpty()) {
        focusRequesterMap[blocks.last().id] = lastBlockFocusRequester
    }
    if (firstBlockFocusRequester != null && blocks.isNotEmpty()) {
        focusRequesterMap[blocks.first().id] = firstBlockFocusRequester
    }

    // 포커스 의도는 UI 비의존 coordinator가 단일 소유하고, Compose는 실행과 화면 갱신만 담당한다.
    val focusCoordinator = remember(focusEpoch) { EditorFocusCoordinator() }
    var focusCoordinatorVersion by remember(focusCoordinator) {
        mutableStateOf(focusCoordinator.version)
    }
    fun enqueueFocus(
        blockId: String,
        cursorHint: CursorHint? = null,
        preferBottomEntry: Boolean = false,
    ): EditorFocusRequest {
        val request = focusCoordinator.request(blockId, cursorHint, preferBottomEntry)
        MarkdownImeDiagnostics.log("focus-request|origin=navigation|request=${request.id}|block=${blockId.hashCode()}|hint=${cursorHint?.let { it::class.simpleName }}")
        focusCoordinatorVersion = focusCoordinator.version
        return request
    }
    fun enqueueFocus(intent: EditorFocusIntent, origin: String = "mutation"): EditorFocusRequest {
        val request = focusCoordinator.request(intent)
        MarkdownImeDiagnostics.log("focus-request|origin=$origin|request=${request.id}|block=${intent.targetBlockId.hashCode()}|hint=${intent.cursorHint?.let { it::class.simpleName }}")
        focusCoordinatorVersion = focusCoordinator.version
        return request
    }

    // Multi selection 입력 치환이나 selection 해제 뒤의 일회성 위치만 받는다.
    // History는 field별 cursor selection을 복원하지만 focus 소유권은 추적하지 않는다.
    val documentInputFocusRequest = LocalDocumentInputFocusRequest.current
    val latestDocumentInputFocusRequest by rememberUpdatedState(documentInputFocusRequest)
    var pendingDocumentFocus by remember(focusCoordinator) {
        mutableStateOf<PendingDocumentFocus?>(null)
    }

    fun completeDocumentFocus(coordinatorRequestId: Long) {
        val pending = pendingDocumentFocus
            ?.takeIf { it.coordinatorRequestId == coordinatorRequestId }
            ?: return
        val currentRequest = latestDocumentInputFocusRequest
        if (currentRequest?.id == pending.documentRequestId) {
            currentRequest.onFocusTransferCompleted(currentRequest.id)
        }
        pendingDocumentFocus = null
    }

    fun completeFocusRequest(request: EditorFocusRequest) {
        if (focusCoordinator.complete(request)) {
            focusCoordinatorVersion = focusCoordinator.version
        }
        completeDocumentFocus(request.id)
    }

    LaunchedEffect(documentInputFocusRequest?.id) {
        val request = documentInputFocusRequest ?: return@LaunchedEffect
        val endpoint = request.endpoint
        if (endpoint.containerPath != containerPath) {
            val isDescendant = endpoint.containerPath.size > containerPath.size &&
                endpoint.containerPath.take(containerPath.size) == containerPath
            if (isDescendant && !isNested) {
                val containingBlockId = endpoint.containerPath[containerPath.size]
                val targetIndex = blocks.indexOfFirst { it.id == containingBlockId }
                val visibleIndices = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                if (targetIndex >= 0 && targetIndex !in visibleIndices) {
                    lazyListState.animateScrollToItem(targetIndex)
                }
            }
            return@LaunchedEffect
        }
        if (endpoint.blockId !in currentIds) {
            return@LaunchedEffect
        }
        val coordinatorRequest = enqueueFocus(
            EditorFocusIntent(
                targetBlockId = endpoint.blockId,
                // Text replacement handoff 중에는 새 TextFieldState가 이미 정확한 cursor를 가진다.
                // focus 이후 AtOffset을 다시 적용하면 그 사이 실제 field가 받은 입력의 cursor를 되돌릴 수 있다.
                cursorHint = if (request.isTextReplacementHandoff) {
                    null
                } else {
                    request.cursorHint
                },
            ),
            origin = "document-input",
        )
        pendingDocumentFocus = PendingDocumentFocus(
            documentRequestId = request.id,
            coordinatorRequestId = coordinatorRequest.id,
        )
    }

    val pendingFocusRequest = focusCoordinator.currentRequest()
    LaunchedEffect(focusCoordinator, focusCoordinatorVersion, currentIds) {
        val request = focusCoordinator.currentRequest() ?: return@LaunchedEffect
        val id = request.targetBlockId
        if (id !in currentIds) {
            if (focusCoordinator.cancel(request)) {
                focusCoordinatorVersion = focusCoordinator.version
            }
            completeDocumentFocus(request.id)
            return@LaunchedEffect
        }
        // 대상 블록이 화면 밖이면 스크롤
        if (!isNested) {
            val targetIndex = blocks.indexOfFirst { it.id == id }
            if (targetIndex >= 0) {
                var info = lazyListState.layoutInfo
                var item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                if (item == null) {
                    lazyListState.animateScrollToItem(targetIndex)
                    if (!focusCoordinator.isCurrent(request)) return@LaunchedEffect
                    info = lazyListState.layoutInfo
                    item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                }
                if (item != null) {
                    val delta = editorItemScrollDelta(
                        itemOffset = item.offset,
                        itemSize = item.size,
                        viewportStart = info.viewportStartOffset,
                        viewportEnd = info.viewportEndOffset,
                        preferEnd = request.preferBottomEntry,
                    )
                    if (delta != 0f) lazyListState.animateScrollBy(delta)
                }
            }
        }
        val hint = request.cursorHint
        val targetBlock = blocks.find { it.id == id }
        // A table owns a FocusRequester per cell. Once its block is visible, let the child focus
        // the exact restored cell instead of briefly focusing the first cell here.
        if (hint is CursorHint.TableCell && targetBlock is EditorBlock.Table) {
            return@LaunchedEffect
        }
        if (hint is CursorHint.CalloutBodyEnd && targetBlock is EditorBlock.Callout) {
            return@LaunchedEffect
        }
        // ↑ 진입 시 bottomEntryFRMap 우선, 없으면 focusRequesterMap fallback
        val fallbackFR = focusRequesterMap[id]
        val targetFR = if (request.preferBottomEntry) {
            bottomEntryFRMap[id] ?: focusRequesterMap[id]
        } else {
            focusRequesterMap[id]
        }
        kotlinx.coroutines.delay(50.milliseconds)
        if (!focusCoordinator.isCurrent(request)) return@LaunchedEffect
        val focusCandidates = listOfNotNull(targetFR, fallbackFR).distinct()
        fun requestFirstAttached(): Boolean = focusCandidates.any { requester ->
            MarkdownImeDiagnostics.log("focus-request|phase=before|request=${request.id}|block=${id.hashCode()}")
            val applied = runCatching { requester.requestFocus() }.getOrDefault(false)
            MarkdownImeDiagnostics.log("focus-request|phase=after|request=${request.id}|block=${id.hashCode()}|applied=$applied")
            applied
        }
        var focusApplied = requestFirstAttached()
        if (!focusApplied) {
            kotlinx.coroutines.delay(100.milliseconds)
            if (!focusCoordinator.isCurrent(request)) return@LaunchedEffect
            focusApplied = requestFirstAttached()
        }
        if (!focusApplied) {
            if (focusCoordinator.cancel(request)) {
                focusCoordinatorVersion = focusCoordinator.version
            }
            completeDocumentFocus(request.id)
            return@LaunchedEffect
        }
        // 포커스 후 커서 위치 설정
        if (hint != null) {
            kotlinx.coroutines.delay(10.milliseconds)
            if (!focusCoordinator.isCurrent(request)) return@LaunchedEffect
            // AtX 힌트: 대상이 Text가 아니면 Start/End로 변환
            val effectiveHint = if (hint is CursorHint.AtX && targetBlock !is EditorBlock.Text) {
                if (hint.lastLine) CursorHint.End else CursorHint.Start
            } else hint

            if (effectiveHint is CursorHint.Start || effectiveHint is CursorHint.End) {
                // bottomEntry로 포커스한 경우 → Callout body의 TextBlock에 도달했으므로 커서 설정 불필요
                // (focusRequesterMap의 Text/Code에만 적용)
                if (!request.preferBottomEntry) {
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
                            selection = TextRange(offset)
                        }
                    }
                }
            }
            // bottomEntry + Callout + End → body 가장 깊은 마지막 Text 의 cursor 를 End 로 강제 설정.
            // (FocusRequester 만 호출하면 그 블록의 이전 selection 이 복원되어 사용자 의도와 어긋남)
            if (effectiveHint is CursorHint.End && request.preferBottomEntry && targetBlock is EditorBlock.Callout) {
                val lastText = findDeepestLastText(targetBlock.bodyBlocks)
                if (lastText != null) {
                    val len = lastText.textFieldState.text.length
                    lastText.textFieldState.edit {
                        selection = TextRange(len)
                    }
                }
            }
            if (effectiveHint is CursorHint.AtOffset && targetBlock is EditorBlock.Text) {
                val state = targetBlock.textFieldState
                val offset = effectiveHint.offset.coerceIn(0, state.text.length)
                state.edit {
                    selection = TextRange(offset)
                }
            }
            // AtX + Text 대상은 TextBlockEditor 내부에서 정밀 처리
        }
        val waitsForTextLayout = requiresTextLayoutAck(hint, targetBlock)
        if (!waitsForTextLayout) {
            completeFocusRequest(request)
        }
    }

    fun applyMutation(mutation: EditorMutation?) {
        if (mutation == null) return
        if (MarkdownEditorDebugOptions.logImeDiagnostics) {
            val previousById = blocks.associateBy { it.id }
            val stateChanges = mutation.blocks.mapNotNull { updated ->
                val previous = previousById[updated.id]
                val oldState = (previous as? EditorBlock.Text)?.textFieldState
                val newState = (updated as? EditorBlock.Text)?.textFieldState
                if (previous != null && (previous::class != updated::class || oldState !== newState)) {
                    "${previous::class.simpleName}:${oldState?.hashCode()}->${updated::class.simpleName}:${newState?.hashCode()}"
                } else null
            }
            MarkdownImeDiagnostics.log("mutation|states=${stateChanges.joinToString(",")}|hint=${mutation.focusIntent?.cursorHint?.let { it::class.simpleName }}")
        }
        onBlocksChanged(mutation.blocks)
        mutation.focusIntent?.let { intent ->
            focusRequesterMap.getOrPut(intent.targetBlockId) { FocusRequester() }
            enqueueFocus(intent)
        }
    }

    // Cross-block selection 이 Multi 로 갱신될 때마다 focus endpoint가 보이도록 스크롤한다.
    // 실제 키보드/IME focus는 문서 단위 input capture가 소유하므로 독립 TextField 사이를 오가지 않는다.
    val multiSelection = documentSelection?.value as? DocumentSelection.Multi
    val selectionPointerActive = LocalDocumentSelectionPointerActive.current
    val selectionPointerRegistry = LocalDocumentSelectionPointerRegistry.current
    if (multiSelection != null && multiSelection.focus.containerPath == containerPath) {
        val focusTargetId = multiSelection.focus.blockId
        val focusTarget = blocks.firstOrNull { it.id == focusTargetId }
        val preferFocusEnd = when (focusTarget) {
            is EditorBlock.Text -> multiSelection.focus.offset >= focusTarget.textFieldState.text.length
            else -> multiSelection.focus.offset == SelectionEndpoint.ATOMIC_END
        }
        LaunchedEffect(focusTargetId) {
            // Pointer drag owns edge scrolling. The recorded endpoint also covers a fast mouse-up
            // that happens before this effect starts.
            val pointerRevealSkip =
                selectionPointerRegistry?.consumeRevealSkip(containerPath, focusTargetId) == true
            if (pointerRevealSkip || selectionPointerActive?.value == true) return@LaunchedEffect
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
                        val delta = editorItemScrollDelta(
                            itemOffset = item.offset,
                            itemSize = item.size,
                            viewportStart = info.viewportStartOffset,
                            viewportEnd = info.viewportEndOffset,
                            preferEnd = preferFocusEnd,
                        )
                        if (delta != 0f) lazyListState.animateScrollBy(delta)
                    }
                }
            }
        }
    }

    // Cross-block selection 시각화 (Phase 1) — 정규화는 한 번만 계산 후 isBlockInSelection 헬퍼에 전달.
    // 재귀 Callout body 안 selection 은 Step B 의 path 전파 완료 후 자연스럽게 동작.
    val normalizedSelection = (documentSelection?.value as? DocumentSelection.Multi)
        ?.normalizeForContainer(blocks, containerPath)

    @Composable
    fun BlockWithNav(
        index: Int,
        block: EditorBlock,
        modifier: Modifier = Modifier,
        expandTextToParent: Boolean = false,
    ) {
        TrackEditorRecomposition(scope = "block-row", key = block.id)
        val fr = focusRequesterMap[block.id] ?: remember { FocusRequester() }

        // LazyColumn이 아이템 recomposition을 skip해도 콜백이 최신 blocks/index를 참조하도록 보장
        val currentBlocks by rememberUpdatedState(blocks)
        val currentIndex by rememberUpdatedState(index)

        val nav = BlockNavigation(
            focus = BlockFocusActions(
                onMoveToPrevious = {
                    if (currentIndex > 0) {
                        enqueueFocus(
                            currentBlocks[currentIndex - 1].id,
                            CursorHint.End,
                            preferBottomEntry = true,
                        )
                    } else {
                        onEscapeToPrevious()
                    }
                },
                onMoveToNext = {
                    if (currentIndex < currentBlocks.lastIndex) {
                        enqueueFocus(currentBlocks[currentIndex + 1].id, CursorHint.Start)
                    } else {
                        onEscapeToNext()
                    }
                },
                onMoveToPreviousWithX = { cursorX ->
                    if (currentIndex > 0) {
                        enqueueFocus(
                            currentBlocks[currentIndex - 1].id,
                            CursorHint.AtX(cursorX, lastLine = true),
                            preferBottomEntry = true,
                        )
                    } else {
                        onEscapeToPrevious()
                    }
                },
                onMoveToNextWithX = { cursorX ->
                    if (currentIndex < currentBlocks.lastIndex) {
                        enqueueFocus(
                            currentBlocks[currentIndex + 1].id,
                            CursorHint.AtX(cursorX, lastLine = false),
                        )
                    } else {
                        onEscapeToNext()
                    }
                },
                onMoveLeft = {
                    if (currentIndex > 0) {
                        // ↑ 와 동일한 진입 의미: 이전 블록의 마지막 위치(body 끝, 없으면 title 끝)
                        enqueueFocus(
                            currentBlocks[currentIndex - 1].id,
                            CursorHint.End,
                            preferBottomEntry = true,
                        )
                    } else {
                        onEscapeLeft()
                    }
                },
            ),
            mutation = BlockMutationActions(
                onInsertDlCallout = if (block is EditorBlock.Text && !block.rawMode &&
                    excludeCalloutTypes.none { it.equals("DL", ignoreCase = true) }) ({
                    applyMutation(insertDlCallout(currentBlocks, currentIndex))
                }) else null,
                onMergeWithPrevious = {
                    applyMutation(EditorMutationDispatcher.mergeWithPrevious(currentBlocks, currentIndex))
                },
                onSplitBlock = {
                    applyMutation(EditorMutationDispatcher.splitTextBlock(currentBlocks, currentIndex))
                },
                onSplitByEmptyLine = {
                    applyMutation(EditorMutationDispatcher.splitByEmptyLine(currentBlocks, currentIndex))
                },
                onReparse = {
                    applyMutation(
                        EditorMutationDispatcher.reparse(
                            currentBlocks,
                            currentIndex,
                            excludeCalloutTypes,
                        )
                    )
                },
                onReparseSilent = {
                    // focus-out 트리거 reparse — 블록 교체만, focus 는 사용자가 옮긴 곳 그대로 유지
                    applyMutation(
                        EditorMutationDispatcher.reparse(
                            currentBlocks,
                            currentIndex,
                            excludeCalloutTypes,
                            requestFocus = false,
                        ),
                    )
                },
                onDissolveSelf = {
                    // 모든 특수 블록(Code/Callout/Table/Embed) 자기 자신 dissolve 통합 라우팅
                    val mutation = EditorMutationDispatcher.dissolve(currentBlocks, currentIndex)
                    val entry = focusCoordinator.currentRequest()?.takeIf {
                        currentBlocks[currentIndex] is EditorBlock.Embed &&
                            it.targetBlockId == currentBlocks[currentIndex].id
                    }
                    applyMutation(if (entry != null && mutation?.focusIntent != null) {
                        mutation.copy(focusIntent = mutation.focusIntent.copy(cursorHint = entry.cursorHint))
                    } else mutation)
                },
                onClearRawMode = {
                    applyMutation(EditorMutationDispatcher.clearRawMode(currentBlocks, currentIndex))
                },
            ),
            selection = BlockSelectionActions(
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
            ),
        )

        val selected = isBlockInSelection(
            blockIndex = index,
            blocksInContainer = blocks,
            containerPath = containerPath,
            normalizedSelection = normalizedSelection,
        )
        val textSelectionRange = if (selected && block is EditorBlock.Text && normalizedSelection != null) {
            val start = if (normalizedSelection.start.blockId == block.id) {
                normalizedSelection.start.offset.coerceIn(0, block.textFieldState.text.length)
            } else {
                0
            }
            val end = if (normalizedSelection.end.blockId == block.id) {
                normalizedSelection.end.offset.coerceIn(start, block.textFieldState.text.length)
            } else {
                block.textFieldState.text.length
            }
            TextRange(start, end)
        } else {
            null
        }
        val itemBackground = when {
            selected && block !is EditorBlock.Text -> Modifier.background(styleConfig.selectionAccent)
            else -> Modifier
        }
        val pointerRegistry = LocalDocumentSelectionPointerRegistry.current
        val atomicPointerKey = remember(containerPath, block.id) {
            selectionPointerTargetKey(containerPath, block.id, "atomic")
        }
        DisposableEffect(pointerRegistry, atomicPointerKey, block is EditorBlock.Text) {
            onDispose { pointerRegistry?.unregister(atomicPointerKey) }
        }
        val atomicPointerTarget = if (block is EditorBlock.Text || pointerRegistry == null) {
            Modifier
        } else {
            Modifier.onGloballyPositioned { coordinates ->
                pointerRegistry.registerAtomic(
                    key = atomicPointerKey,
                    coordinates = coordinates,
                    containerPath = containerPath,
                    blockId = block.id,
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = modifier
                    .then(editorBlockWidthModifier(block))
                    .then(atomicPointerTarget)
                    .then(itemBackground),
            ) {
                val blockFocusRequest = pendingFocusRequest
                    ?.takeIf { it.targetBlockId == block.id }
                val isLastBlockForRegistration = index == blocks.lastIndex
                val latestLastBlockRegistration by rememberUpdatedState(onLastBlockBottomEntryRegistered)
                val bottomEntryRegistration = remember(block.id, isLastBlockForRegistration) {
                    { frOrNull: FocusRequester? ->
                        if (frOrNull != null) {
                            bottomEntryFRMap[block.id] = frOrNull
                        } else {
                            bottomEntryFRMap.remove(block.id)
                        }
                        if (isLastBlockForRegistration) {
                            latestLastBlockRegistration(frOrNull)
                        }
                    }
                }
                BlockItem(
                    block = block,
                    modifier = if (expandTextToParent) Modifier.fillMaxHeight() else Modifier,
                    styleConfig = styleConfig,
                    textStyle = textStyle,
                    cursorBrush = cursorBrush,
                    focusRequester = fr,
                    navigation = nav,
                    cursorHint = blockFocusRequest?.cursorHint,
                    cursorHintRequestId = blockFocusRequest?.id,
                    navigationRequest = navigationRequest?.takeIf {
                        it.id == readyNavigationRequestId && it.blockId == block.id
                    },
                    onNavigationTargetPositioned = { requestId, targetTopInRoot ->
                        if (latestNavigationRequest?.id == requestId) {
                            positionedNavigationTarget = requestId to targetTopInRoot
                        }
                    },
                    onCursorHintApplied = { requestId ->
                        focusCoordinator.currentRequest()
                            ?.takeIf { it.id == requestId }
                            ?.let(::completeFocusRequest)
                    },
                    onBlocksChanged = onBlocksChanged,
                    allBlocks = blocks,
                    blockIndex = index,
                    enableEnterEscape = enableEnterEscape,
                    documentSelection = documentSelection,
                    documentSelectionRange = textSelectionRange,
                    containerPath = containerPath,
                    onRegisterBottomEntryFR = bottomEntryRegistration,
                    focusEpoch = focusEpoch,
                )
            }
        }
    }

    if (isNested) {
        Column(modifier = modifier) {
            for ((index, block) in blocks.withIndex()) {
                key(block.id) {
                    BlockWithNav(index, block)
                }
            }
        }
    } else {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides editorBringIntoViewSpec,
            LocalEditorFullBringIntoView provides fullBringIntoView,
        ) {
            LazyColumn(
                modifier = modifier.onGloballyPositioned { coordinates ->
                    viewportTopInRoot = coordinates.localToRoot(Offset.Zero).y
                },
                state = lazyListState,
            ) {
                itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
                    BlockWithNav(
                        index = index,
                        block = block,
                        modifier = if (expandEmptyRootInput) {
                            Modifier.fillParentMaxHeight()
                        } else {
                            Modifier
                        },
                        expandTextToParent = expandEmptyRootInput,
                    )
                }
                if (trailingTextBlock != null) {
                    item(key = trailingTextBlock.id) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            TrailingTextInput(
                                block = trailingTextBlock,
                                modifier = Modifier
                                    .widthIn(max = MarkdownEditorStyleTokens.desktopMaxWidth)
                                    .fillMaxWidth()
                                    .heightIn(min = trailingInputHeight),
                                blocks = blocks,
                                styleConfig = styleConfig,
                                textStyle = textStyle,
                                cursorBrush = cursorBrush,
                                onMoveToPrevious = {
                                    val previous = blocks.lastOrNull() ?: return@TrailingTextInput
                                    enqueueFocus(
                                        blockId = previous.id,
                                        cursorHint = CursorHint.End,
                                        preferBottomEntry = previous is EditorBlock.Callout ||
                                            previous is EditorBlock.Table,
                                    )
                                },
                                onMaterialized = { updatedBlocks, materialized ->
                                    onBlocksChanged(updatedBlocks)
                                    focusRequesterMap.getOrPut(materialized.id) { FocusRequester() }
                                    enqueueFocus(
                                        blockId = materialized.id,
                                        // Materialization keeps this field's live selection. A delayed
                                        // captured offset would overwrite typing during the handoff.
                                    )
                                },
                                onInsertDlCallout = if (excludeCalloutTypes.none { it.equals("DL", true) }) ({
                                    val updated = if (blocks.any { it.id == trailingTextBlock.id }) blocks
                                        else blocks + trailingTextBlock
                                    applyMutation(insertDlCallout(updated, updated.indexOfFirst { it.id == trailingTextBlock.id }))
                                }) else null,
                            )
                        }
                    }
                }
                if (!expandEmptyRootInput && blocks.isNotEmpty()) {
                    item(key = "editor-bottom-padding") {
                        Spacer(Modifier.height(MarkdownEditorStyleTokens.bodyBottomPadding))
                    }
                }
            }
        }
    }
}

private fun editorBlockWidthModifier(block: EditorBlock): Modifier =
    if (block is EditorBlock.Table) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.widthIn(max = MarkdownEditorStyleTokens.desktopMaxWidth).fillMaxWidth()
    }

/** 최상위 문서가 특수 블록으로 끝날 때만 저장되지 않는 빈 입력면을 추가한다. */
internal fun shouldShowTrailingTextInput(
    blocks: List<EditorBlock>,
    isNested: Boolean = false,
): Boolean = !isNested && blocks.isNotEmpty() && blocks.last() !is EditorBlock.Text

/** 빈 최상위 문서의 유일한 입력면만 viewport 높이를 사용한다. */
internal fun shouldExpandEmptyRootInput(
    blocks: List<EditorBlock>,
    isNested: Boolean = false,
): Boolean {
    if (isNested || blocks.size != 1) return false
    val text = blocks.single() as? EditorBlock.Text ?: return false
    return text.textFieldState.text.isEmpty() && !text.rawMode
}

/** 마지막 실제 아이템 아래에서 viewport 끝까지 남은 세로 공간을 계산한다. */
internal fun remainingTrailingInputHeightPx(
    viewportEndOffset: Int,
    lastRealItemEndOffset: Int,
): Int = (viewportEndOffset - lastRealItemEndOffset).coerceAtLeast(0)

/** IME 조합 중간값은 문서 블록으로 승격하지 않고 확정된 첫 편집만 받는다. */
internal fun shouldMaterializeTrailingTextInput(
    text: String,
    hasComposition: Boolean,
): Boolean = text.isNotEmpty() && !hasComposition

/** 가상 입력면을 같은 ID와 TextFieldState를 가진 실제 마지막 TextBlock으로 승격한다. */
internal fun materializeTrailingTextInput(
    blocks: List<EditorBlock>,
    trailingBlock: EditorBlock.Text,
): List<EditorBlock>? {
    if (!shouldShowTrailingTextInput(blocks)) return null
    if (trailingBlock.textFieldState.text.isEmpty()) return null
    return blocks + trailingBlock
}

@Composable
private fun TrailingTextInput(
    block: EditorBlock.Text,
    modifier: Modifier = Modifier,
    blocks: List<EditorBlock>,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    onMoveToPrevious: () -> Unit,
    onMaterialized: (List<EditorBlock>, EditorBlock.Text) -> Unit,
    onInsertDlCallout: (() -> Unit)? = null,
) {
    val latestBlocks by rememberUpdatedState(blocks)
    val latestOnMaterialized by rememberUpdatedState(onMaterialized)

    LaunchedEffect(block.textFieldState) {
        snapshotFlow {
            block.textFieldState.text.toString() to (block.textFieldState.composition != null)
        }
            .filter { (text, hasComposition) ->
                shouldMaterializeTrailingTextInput(text, hasComposition)
            }
            .first()

        val updated = materializeTrailingTextInput(latestBlocks, block)
            ?: return@LaunchedEffect
        latestOnMaterialized(updated, block)
    }

    TextBlockEditor(
        block = block,
        modifier = modifier,
        styleConfig = styleConfig,
        textStyle = textStyle,
        cursorBrush = cursorBrush,
        navigation = BlockNavigation(
            focus = BlockFocusActions(
                onMoveToPrevious = onMoveToPrevious,
                onMoveToPreviousWithX = { onMoveToPrevious() },
                onMoveLeft = onMoveToPrevious,
            ),
            mutation = BlockMutationActions(
                onInsertDlCallout = onInsertDlCallout,
                onMergeWithPrevious = onMoveToPrevious,
            ),
        ),
    )
}

@Composable
private fun BlockItem(
    block: EditorBlock,
    modifier: Modifier = Modifier,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    focusRequester: FocusRequester,
    navigation: BlockNavigation,
    cursorHint: CursorHint? = null,
    cursorHintRequestId: Long? = null,
    navigationRequest: EditorNavigationRequest? = null,
    onNavigationTargetPositioned: (Long, Float) -> Unit = { _, _ -> },
    onCursorHintApplied: (Long) -> Unit = {},
    onBlocksChanged: (List<EditorBlock>) -> Unit,
    allBlocks: List<EditorBlock>,
    blockIndex: Int,
    enableEnterEscape: Boolean = false,
    /** Callout 분기에서 body 재귀 호출에 전파 — Step B-2c body 안 cross-selection 활성 */
    documentSelection: MutableState<DocumentSelection>? = null,
    documentSelectionRange: TextRange? = null,
    containerPath: List<String> = emptyList(),
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit = {},
    focusEpoch: Any = Unit,
) {
    TrackEditorRecomposition(scope = "block", key = block.id)
    // LazyColumn이 아이템 recomposition을 skip해도 클로저가 최신 값을 참조하도록 보장
    val latestAllBlocks by rememberUpdatedState(allBlocks)
    val latestBlockIndex by rememberUpdatedState(blockIndex)

    // Block 유형(Code/Callout/Table) 의 state-empty 자동 격하는 두지 않는다 (docs/markdown-editor.md).
    // 자동 격하의 본래 의도는 raw 블록의 마커 깨짐을 일반 텍스트로 정리하는 것이며 이는 tryReparse 가 처리.
    // 박스 UI 자체의 state-empty 트리거는 사용자가 title 잠깐 비운 채 다시 입력하려는 단순 편집에서도
    // 박스가 사라지는 부작용이 있어 제거됨.

    when (block) {
        is EditorBlock.Text, is EditorBlock.Embed -> {
            val sourceBlock = block as? EditorBlock.Text
            val source = sourceBlock?.textFieldState?.text?.toString() ?: block.toMarkdown()
            val activeEmbedSource = sourceBlock?.takeUnless { it.rawOrigin == RawOrigin.CODE }?.textFieldState?.selection?.let {
                markdownEmbedSourceAtSelection(source, it)
            }
            val target = ((block as? EditorBlock.Embed)?.target ?: standaloneMarkdownEmbedTarget(source)
                ?: activeEmbedSource?.let(::standaloneMarkdownEmbedTarget))
                ?.substringBefore('|')?.trim()
            val composing = sourceBlock?.textFieldState?.composition != null
            var previewTarget by remember(block.id) { mutableStateOf(target.takeUnless { composing }) }
            var sourceFocused by remember(block.id) { mutableStateOf(false) }
            LaunchedEffect(target, composing) {
                if (!composing) {
                    if (target != previewTarget) kotlinx.coroutines.delay(500.milliseconds)
                    previewTarget = target
                    if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                        MarkdownImeDiagnostics.log("preview|phase=publish|present=${target != null}|state=${sourceBlock?.textFieldState?.hashCode()}|selection=${sourceBlock?.textFieldState?.selection}|composition=${sourceBlock?.textFieldState?.composition}")
                    }
                }
            }
            val handlers = LocalMarkdownInteractionHandlers.current
            Column(
                modifier.then(
                    if (sourceBlock == null) Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) navigation.mutation.onDissolveSelf()
                        }
                        .focusable()
                    else Modifier,
                ),
            ) {
                if (sourceBlock != null) {
                    // Keep the same input field while typing a standalone Embed; parse only on focus-out.
                    TextBlockEditor(
                        block = sourceBlock,
                        deferStandaloneEmbedParsing = target != null || activeEmbedSource != null,
                        modifier = Modifier.onFocusChanged { sourceFocused = it.isFocused },
                        styleConfig = styleConfig,
                        textStyle = textStyle,
                        cursorBrush = cursorBrush,
                        focusRequester = focusRequester,
                        navigation = navigation,
                        cursorHint = cursorHint,
                        cursorHintRequestId = cursorHintRequestId,
                        navigationRequest = navigationRequest,
                        onNavigationTargetPositioned = onNavigationTargetPositioned,
                        onCursorHintApplied = onCursorHintApplied,
                        escapeOnEmptyEnter = enableEnterEscape,
                        documentSelectionRange = documentSelectionRange,
                        selectionContainerPath = containerPath,
                        renderContext = if (containerPath.isEmpty()) {
                            MarkdownRenderContext.RootText
                        } else {
                            MarkdownRenderContext.NestedText
                        },
                    )
                }
                previewTarget?.let { preview ->
                    MarkdownEmbedPreviewSurface(
                        target = preview,
                        styleConfig = styleConfig,
                        textStyle = textStyle,
                        onOpen = handlers.openInternalLink,
                        onEdit = {
                            if (sourceBlock == null) navigation.mutation.onDissolveSelf()
                            else if (!sourceFocused) focusRequester.requestFocus()
                        },
                    )
                }
            }
        }
        is EditorBlock.Callout -> CalloutBlockEditor(
            block = block,
            styleConfig = styleConfig,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            focusRequester = focusRequester,
            navigation = navigation,
            cursorHint = cursorHint,
            cursorHintRequestId = cursorHintRequestId,
            onCursorHintApplied = onCursorHintApplied,
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
            focusEpoch = focusEpoch,
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
            cursorHint = cursorHint,
            cursorHintRequestId = cursorHintRequestId,
            onCursorHintApplied = onCursorHintApplied,
            focusEpoch = focusEpoch,
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
