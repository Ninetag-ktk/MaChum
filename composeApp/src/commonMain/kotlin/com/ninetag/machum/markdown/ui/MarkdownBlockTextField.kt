package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.CalloutDecorationStyle
import com.ninetag.machum.markdown.service.CalloutLayout
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.service.markdownHeadingColor
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.CursorHint
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.EditorDocumentSnapshot
import com.ninetag.machum.markdown.state.EditorDocumentValueCoordinator
import com.ninetag.machum.markdown.state.EditorHistory
import com.ninetag.machum.markdown.state.EditorHistoryBoundary
import com.ninetag.machum.markdown.state.EditorHistoryFocusTarget
import com.ninetag.machum.markdown.state.captureEditorDocumentSnapshot
import com.ninetag.machum.markdown.state.classifyEditorHistoryTransaction
import com.ninetag.machum.markdown.state.findEditorHistoryFocusTarget
import com.ninetag.machum.markdown.state.continueTextInputAt
import com.ninetag.machum.markdown.state.restoreBlocks
import com.ninetag.machum.markdown.state.replaceSelectedText
import com.ninetag.machum.markdown.state.replaceSelectedMarkdown
import com.ninetag.machum.markdown.state.toMarkdown
import com.ninetag.machum.markdown.state.MarkdownBlockParser
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.MarkdownBlockReferenceCreation
import com.ninetag.machum.markdown.state.applyCurrentDocumentBlockReference
import com.ninetag.machum.markdown.state.MarkdownEmbedPreview
import com.ninetag.machum.markdown.state.MarkdownNavigationTarget
import com.ninetag.machum.markdown.state.markdownNavigationOffset
import com.ninetag.machum.markdown.state.SelectionEndpoint
import com.ninetag.machum.markdown.ui.selection.DocumentInputFocusRequest
import com.ninetag.machum.markdown.ui.selection.DocumentSelectionInputCapture
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelection
import com.ninetag.machum.markdown.ui.selection.LocalDocumentInputFocusRequest
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelectionPointerRegistry
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelectionPointerActive
import com.ninetag.machum.markdown.ui.selection.DocumentSelectionPointerRegistry
import com.ninetag.machum.markdown.ui.selection.documentSelectionShortcuts
import com.ninetag.machum.markdown.ui.selection.documentSelectionMouseDrag
import com.ninetag.machum.markdown.ui.diagnostics.TrackEditorRecomposition

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ninetag.machum.theme.semanticColors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal val LocalEditorHistoryBoundary = compositionLocalOf<EditorHistoryBoundary?> { null }
internal val LocalMarkdownCompletionRevision = compositionLocalOf<Any> { Unit }

/**
 * v2 블록 기반 마크다운 에디터의 공개 API.
 *
 * v1 `MarkdownBasicTextField`와 동일한 `value`/`onValueChange` 인터페이스를 유지하여
 * EditorPage에서 drop-in replacement로 사용 가능.
 *
 * 내부적으로:
 * 1. `value` → `MarkdownBlockParser.parse()` → `List<EditorBlock>`
 * 2. `MarkdownBlockEditor`로 블록별 렌더링
 * 3. 블록 변경 → `toMarkdown()` → `onValueChange` 콜백
 */
@Composable
fun MarkdownBlockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig(),
    documentKey: Any = Unit,
    isActive: Boolean = true,
    completionRevision: Any = Unit,
    onOpenExternalLink: (String) -> Unit = {},
    onOpenInternalLink: (String) -> Unit = {},
    onCompleteInternalLink: (MarkdownLinkCompletionRequest) -> List<MarkdownLinkCompletionCandidate> = { emptyList() },
    onCreateBlockReference: suspend (MarkdownBlockReferenceCreation, () -> Boolean) -> String? = { _, _ -> null },
    onBlockReferenceError: (String) -> Unit = {},
    isInternalLinkResolved: (String) -> Boolean = { true },
    onResolveEmbed: suspend (String) -> MarkdownEmbedPreview = {
        MarkdownEmbedPreview.Unavailable("임베드 대상을 찾을 수 없습니다.")
    },
    navigationTarget: MarkdownNavigationTarget? = null,
    onNavigationHandled: (Long) -> Unit = {},
    showQuickBar: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    key(documentKey) {
        MarkdownBlockTextFieldContent(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            styleConfig = styleConfig,
            diagnosticsKey = documentKey.hashCode().toString(16),
            isActive = isActive,
            onOpenExternalLink = onOpenExternalLink,
            onOpenInternalLink = onOpenInternalLink,
            onCompleteInternalLink = onCompleteInternalLink,
            onCreateBlockReference = onCreateBlockReference,
            onBlockReferenceError = onBlockReferenceError,
            isInternalLinkResolved = isInternalLinkResolved,
            onResolveEmbed = onResolveEmbed,
            navigationTarget = navigationTarget,
            onNavigationHandled = onNavigationHandled,
            completionRevision = completionRevision,
            showQuickBar = showQuickBar,
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun MarkdownBlockTextFieldContent(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    textStyle: TextStyle,
    cursorBrush: Brush,
    styleConfig: MarkdownStyleConfig,
    diagnosticsKey: String,
    isActive: Boolean,
    onOpenExternalLink: (String) -> Unit,
    onOpenInternalLink: (String) -> Unit,
    onCompleteInternalLink: (MarkdownLinkCompletionRequest) -> List<MarkdownLinkCompletionCandidate>,
    onCreateBlockReference: suspend (MarkdownBlockReferenceCreation, () -> Boolean) -> String?,
    onBlockReferenceError: (String) -> Unit,
    isInternalLinkResolved: (String) -> Boolean,
    onResolveEmbed: suspend (String) -> MarkdownEmbedPreview,
    navigationTarget: MarkdownNavigationTarget?,
    onNavigationHandled: (Long) -> Unit,
    completionRevision: Any,
    showQuickBar: Boolean,
    contentPadding: PaddingValues,
) {
    TrackEditorRecomposition(scope = "document", key = diagnosticsKey)
    val initialBlocks = remember { parseEditorDocument(value) }
    var blocks by remember { mutableStateOf(initialBlocks) }
    val valueCoordinator = remember { EditorDocumentValueCoordinator(value) }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val firstBlockFocusRequester = remember { FocusRequester() }
    var firstBlockFocusRequestId by remember { mutableStateOf(0L) }
    var nextInputFocusRequestId by remember { mutableStateOf(0L) }
    var historyFocusEpoch by remember { mutableStateOf(0L) }
    var wasActive by remember { mutableStateOf(isActive) }
    val rootLazyListState = rememberLazyListState()
    var navigationRequest by remember { mutableStateOf<EditorNavigationRequest?>(null) }
    var pendingViewportAnchor by remember { mutableStateOf<EditorViewportAnchor?>(null) }
    var pendingHistoryFocusTarget by remember { mutableStateOf<EditorHistoryFocusTarget?>(null) }
    var documentInputFocusRequest by remember {
        mutableStateOf<DocumentInputFocusRequest?>(null)
    }

    // Cross-block selection 상태 (Phase 1) — 최상위에서만 호이스팅. 재귀 Callout body 는 자체 미관리
    val documentSelection = remember { mutableStateOf<DocumentSelection>(DocumentSelection.None) }
    val selectionPointerRegistry = remember { DocumentSelectionPointerRegistry() }
    val selectionPointerActive = remember { mutableStateOf(false) }
    val selectionPointerDragged = remember { mutableStateOf(false) }
    val history = remember {
        EditorHistory(
            captureEditorDocumentSnapshot(initialBlocks),
        )
    }
    val historyBoundary = remember { EditorHistoryBoundary() }
    val quickBarTargets = remember { EditorQuickBarTargets() }
    var historyRevision by remember { mutableStateOf(0L) }

    val latestOnNavigationHandled by rememberUpdatedState(onNavigationHandled)
    LaunchedEffect(navigationTarget?.id) {
        val target = navigationTarget ?: return@LaunchedEffect
        navigationRequest = null
        val offset = markdownNavigationOffset(blocks.toMarkdown(), target)
        val blockOffset = offset?.let { markdownBlockOffsetAt(blocks, it) }
        if (blockOffset == null) {
            latestOnNavigationHandled(target.id)
            return@LaunchedEffect
        }
        val block = blocks[blockOffset.first]
        navigationRequest = EditorNavigationRequest(
            id = target.id,
            blockId = block.id,
            sourceOffset = blockOffset.second,
        )
    }

    fun requestDocumentInputFocus(
        endpoint: SelectionEndpoint,
        isTextReplacementHandoff: Boolean = false,
        cursorHint: CursorHint? = CursorHint.AtOffset(endpoint.offset),
    ) {
        val requestId = ++nextInputFocusRequestId
        documentInputFocusRequest = DocumentInputFocusRequest(
            id = requestId,
            endpoint = endpoint,
            cursorHint = cursorHint,
            isTextReplacementHandoff = isTextReplacementHandoff,
            onFocusTransferCompleted = { completedRequestId ->
                if (documentInputFocusRequest?.id == completedRequestId) {
                    documentInputFocusRequest = null
                }
            },
        )
    }

    LaunchedEffect(isActive, firstBlockFocusRequestId) {
        if (!isActive || firstBlockFocusRequestId == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(50.milliseconds)
        try {
            firstBlockFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    // 외부 변경은 별도 effect에서 새 블록 수명으로 교체한다. 내부 입력의 부모 echo는 재파싱하지 않는다.
    LaunchedEffect(value) {
        if (valueCoordinator.acceptExternal(value)) {
            val externalBlocks = parseEditorDocument(value)
            documentSelection.value = DocumentSelection.None
            firstBlockFocusRequestId = 0L
            documentInputFocusRequest = null
            pendingHistoryFocusTarget = null
            pendingViewportAnchor = null
            historyFocusEpoch++
            historyBoundary.clear()
            history.reset(captureEditorDocumentSnapshot(externalBlocks))
            historyRevision++
            blocks = externalBlocks
        }
    }

    // 블록 내 TextFieldState 변경 감지 → raw markdown 직렬화 → onValueChange
    // collector가 만들어진 시점의 revision을 고정해 외부 교체 전 블록의 늦은 방출을 차단한다.
    val collectorRevision = valueCoordinator.revision
    LaunchedEffect(blocks, value, collectorRevision) {
        snapshotFlow { blocks.toMarkdown() to blocks.hasActiveImeComposition() }
            .distinctUntilChanged()
            .collectLatest { (markdown, hasComposition) ->
                if (hasComposition) return@collectLatest
                if (
                    valueCoordinator.acceptInternal(
                        value = markdown,
                        expectedExternalValue = value,
                        collectorRevision = collectorRevision,
                    )
                ) {
                    val nextSnapshot = captureEditorDocumentSnapshot(blocks)
                    val classifiedTransaction = classifyEditorHistoryTransaction(
                        previous = history.current.blocks,
                        next = nextSnapshot.blocks,
                        occurredAtMillis = Clock.System.now().toEpochMilliseconds(),
                    )
                    history.record(
                        snapshot = nextSnapshot,
                        transaction = historyBoundary.consume(classifiedTransaction),
                    )
                    historyRevision++
                    latestOnValueChange(markdown)
                }
            }
    }

    fun restoreHistorySnapshot(
        snapshot: EditorDocumentSnapshot,
        focusTarget: EditorHistoryFocusTarget?,
    ) {
        val targetRootBlockId = focusTarget?.endpoint?.let { endpoint ->
            endpoint.containerPath.firstOrNull() ?: endpoint.blockId
        }
        val targetIndex = targetRootBlockId?.let { id -> blocks.indexOfFirst { it.id == id } }
        val targetIsVisible = targetIndex != null && targetIndex >= 0 &&
            rootLazyListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        pendingViewportAnchor = if (focusTarget == null || targetIsVisible) {
            captureEditorViewportAnchor(
                blocks = blocks,
                firstVisibleItemIndex = rootLazyListState.firstVisibleItemIndex,
                scrollOffset = rootLazyListState.firstVisibleItemScrollOffset,
            )
        } else {
            // Off-screen history targets move directly through the focus coordinator. Restoring
            // the old anchor first would create a round trip and race the same LazyListState.
            null
        }
        documentSelection.value = DocumentSelection.None
        firstBlockFocusRequestId = 0L
        documentInputFocusRequest = null
        pendingHistoryFocusTarget = focusTarget
        historyFocusEpoch++
        blocks = snapshot.restoreBlocks(reusing = blocks)
    }

    LaunchedEffect(isActive) {
        if (wasActive && !isActive) {
            pendingViewportAnchor = null
            documentSelection.value = DocumentSelection.None
            firstBlockFocusRequestId = 0L
            documentInputFocusRequest = null
            pendingHistoryFocusTarget = null
            historyFocusEpoch++
            historyBoundary.clear()
            history.reset(captureEditorDocumentSnapshot(blocks))
            historyRevision++
        }
        wasActive = isActive
    }

    LaunchedEffect(historyFocusEpoch) {
        val anchor = pendingViewportAnchor
        if (anchor != null) {
            withFrameNanos { }
            resolveEditorViewportIndex(anchor, blocks)?.let { index ->
                rootLazyListState.scrollToItem(index, anchor.scrollOffset)
            }
            // Focus/layout callbacks posted by the restored BasicTextFields run on the following frame.
            // Keep automatic bring-into-view suppressed until those callbacks have drained.
            withFrameNanos { }
            pendingViewportAnchor = null
        }
        val focusTarget = pendingHistoryFocusTarget
        if (anchor == null && focusTarget != null) {
            // Off-screen and newly restored targets reveal their root block first. Publishing the
            // nested field request only after this scroll completes prevents competing mutations
            // of the same LazyListState.
            withFrameNanos { }
            val rootBlockId = focusTarget.endpoint.containerPath.firstOrNull()
                ?: focusTarget.endpoint.blockId
            val targetIndex = blocks.indexOfFirst { it.id == rootBlockId }
            val targetVisible = rootLazyListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
            if (targetIndex >= 0 && !targetVisible) {
                rootLazyListState.animateScrollToItem(targetIndex)
            }
        }
        pendingHistoryFocusTarget = null
        focusTarget?.let { target ->
            requestDocumentInputFocus(
                endpoint = target.endpoint,
                cursorHint = target.cursorHint,
            )
        }
    }

    fun undo(): Boolean {
        val currentSnapshot = captureEditorDocumentSnapshot(blocks)
        val snapshot = history.undo() ?: return false
        historyRevision++
        restoreHistorySnapshot(snapshot, findEditorHistoryFocusTarget(currentSnapshot, snapshot))
        return true
    }
    fun redo(): Boolean {
        val currentSnapshot = captureEditorDocumentSnapshot(blocks)
        val snapshot = history.redo() ?: return false
        historyRevision++
        restoreHistorySnapshot(snapshot, findEditorHistoryFocusTarget(currentSnapshot, snapshot))
        return true
    }
    val canUndo = remember(historyRevision) { history.canUndo }
    val canRedo = remember(historyRevision) { history.canRedo }
    val quickBarActions = rememberEditorQuickBarActions(
        targets = quickBarTargets,
        blocks = blocks,
        documentSelection = documentSelection,
        isActive = isActive,
        documentRevision = collectorRevision to historyFocusEpoch,
        hasComposition = blocks.hasActiveImeComposition(),
        canUndo = canUndo,
        canRedo = canRedo,
        historyBoundary = historyBoundary,
        onUndo = ::undo,
        onRedo = ::redo,
        onReplaceDocumentSelection = { selection, replacement ->
            if (documentSelection.value == selection) {
                if (replacement.isEmpty()) {
                    replaceSelectedText(blocks, selection, "")?.let { result ->
                        historyBoundary.markNextChangeAtomic()
                        documentSelection.value = DocumentSelection.None
                        blocks = result.blocks
                        requestDocumentInputFocus(result.focus)
                    }
                } else {
                    replaceSelectedMarkdown(blocks, selection, replacement)?.let { updated ->
                        if (updated.toMarkdown() != blocks.toMarkdown()) historyBoundary.markNextChangeAtomic()
                        documentSelection.value = DocumentSelection.None
                        blocks = updated
                        firstBlockFocusRequestId++
                    }
                }
            }
        },
    )

    // Ctrl+A/C/Esc + 방향키 자동 해제 단축키 — Modifier 확장 헬퍼 (selection/SelectionUiHelpers.kt) 호출.
    val shortcutHandler = Modifier.documentSelectionShortcuts(
        rootBlocks = blocks,
        documentSelection = documentSelection,
        enabled = isActive,
        onBlocksChanged = { blocks = it },
        onSelectionFocusRequested = { requestDocumentInputFocus(it) },
        onFallbackFocusRequested = { firstBlockFocusRequestId++ },
        onUndo = ::undo,
        onRedo = ::redo,
    )

    // native BasicTextField selection 색과 documentSelection 시각화 색을 통합:
    // 사용자가 어느 메커니즘으로 selection 을 만들든 같은 색으로 보이도록 LocalTextSelectionColors
    // 의 backgroundColor 를 styleConfig.selectionAccent 로 동기화. handleColor 는 기존 값 유지
    // (handle 은 진한 색이어야 자연스러움).
    val existingSelectionColors = LocalTextSelectionColors.current
    val unifiedSelectionColors = remember(existingSelectionColors, styleConfig.selectionAccent) {
        TextSelectionColors(
            handleColor = existingSelectionColors.handleColor,
            backgroundColor = styleConfig.selectionAccent,
        )
    }
    val cancelPendingHistoryFocus = remember {
        {
            if (
                documentInputFocusRequest != null ||
                pendingHistoryFocusTarget != null ||
                pendingViewportAnchor != null
            ) {
                documentInputFocusRequest = null
                pendingHistoryFocusTarget = null
                pendingViewportAnchor = null
                historyFocusEpoch++
            }
        }
    }
    val latestOpenExternalLink = rememberUpdatedState(onOpenExternalLink)
    val latestOpenInternalLink = rememberUpdatedState(onOpenInternalLink)
    val latestCompleteInternalLink = rememberUpdatedState(onCompleteInternalLink)
    val latestCreateBlockReference = rememberUpdatedState(onCreateBlockReference)
    val latestBlockReferenceError = rememberUpdatedState(onBlockReferenceError)
    val latestActive = rememberUpdatedState(isActive)
    val latestIsInternalLinkResolved = rememberUpdatedState(isInternalLinkResolved)
    val latestResolveEmbed = rememberUpdatedState(onResolveEmbed)
    val interactionHandlers = remember {
        MarkdownInteractionHandlers(
            openExternalLink = { latestOpenExternalLink.value(it) },
            openInternalLink = { latestOpenInternalLink.value(it) },
            completeInternalLink = { latestCompleteInternalLink.value(it) },
            createBlockReference = { creation, isCurrent ->
                latestCreateBlockReference.value(creation) { latestActive.value && isCurrent() }
            },
            applyCurrentDocumentBlockReference = { source, request, candidate ->
                latestActive.value && applyCurrentDocumentBlockReference(blocks, source, request, candidate) { updated ->
                    historyBoundary.markNextChangeAtomic()
                    blocks = updated
                }
            },
            blockReferenceError = { latestBlockReferenceError.value(it) },
            isInternalLinkResolved = { latestIsInternalLinkResolved.value(it) },
            resolveEmbed = { latestResolveEmbed.value(it) },
        )
    }

    CompositionLocalProvider(
        LocalTextSelectionColors provides unifiedSelectionColors,
        LocalDocumentSelection provides documentSelection,
        LocalDocumentInputFocusRequest provides documentInputFocusRequest,
        LocalDocumentSelectionPointerRegistry provides selectionPointerRegistry,
        LocalDocumentSelectionPointerActive provides selectionPointerActive,
        LocalEditorHistoryBoundary provides historyBoundary,
        LocalEditorQuickBarTargets provides quickBarTargets,
        LocalMarkdownInteractionHandlers provides interactionHandlers,
    ) {
        Column(modifier) {
        Box(
            modifier = Modifier.weight(1f).fillMaxSize().padding(contentPadding).then(shortcutHandler.documentSelectionMouseDrag(
                documentSelection = documentSelection,
                registry = selectionPointerRegistry,
                lazyListState = rootLazyListState,
                enabled = isActive,
                onPointerPress = cancelPendingHistoryFocus,
                onPointerGestureChanged = { selectionPointerActive.value = it },
                onPointerDragChanged = { selectionPointerDragged.value = it },
                onSelectionFocusRequested = { requestDocumentInputFocus(it) },
            )),
        ) {
            CompositionLocalProvider(LocalMarkdownCompletionRevision provides completionRevision) {
            MarkdownBlockEditor(
                blocks = blocks,
                onBlocksChanged = { newBlocks -> blocks = newBlocks },
                modifier = Modifier.fillMaxSize(),
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                isNested = false,
                documentSelection = documentSelection,
                containerPath = emptyList(),
                // Deactivation must cancel delayed table/callout/block focus work in the same
                // composition that transfers pager ownership, before the history-reset effect runs.
                focusEpoch = Triple(collectorRevision, historyFocusEpoch, isActive),
                firstBlockFocusRequester = firstBlockFocusRequester,
                rootLazyListState = rootLazyListState,
                navigationRequest = navigationRequest,
                onNavigationHandled = { requestId ->
                    if (navigationRequest?.id == requestId) navigationRequest = null
                    latestOnNavigationHandled(requestId)
                },
                suppressBringIntoView = {
                    pendingViewportAnchor != null ||
                        (selectionPointerActive.value &&
                            (!selectionPointerDragged.value ||
                                documentSelection.value is DocumentSelection.Multi))
                },
            )
            }
            DocumentSelectionInputCapture(
                documentSelection = documentSelection,
                inputFocusRequest = documentInputFocusRequest,
                onTextCommitted = { selection, text ->
                    if (documentSelection.value != selection) return@DocumentSelectionInputCapture
                    val result = replaceSelectedText(blocks, selection, text)
                        ?: return@DocumentSelectionInputCapture
                    documentSelection.value = DocumentSelection.None
                    blocks = result.blocks
                    requestDocumentInputFocus(
                        endpoint = result.focus,
                        isTextReplacementHandoff = true,
                    )
                },
                onHandoffTextCommitted = { request, text ->
                    if (documentInputFocusRequest?.id != request.id) {
                        return@DocumentSelectionInputCapture
                    }
                    val nextFocus = continueTextInputAt(blocks, request.endpoint, text)
                        ?: return@DocumentSelectionInputCapture
                    requestDocumentInputFocus(
                        endpoint = nextFocus,
                        isTextReplacementHandoff = true,
                    )
                },
            )
        }
        if (showQuickBar && isActive &&
            (quickBarTargets.focused != null || documentSelection.value is DocumentSelection.Multi)) {
            EditorQuickBar(quickBarActions.enabled, onCommand = {
                cancelPendingHistoryFocus()
                quickBarActions.execute(it)
            }, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
        }
        }
    }
}

private fun List<EditorBlock>.hasActiveImeComposition(): Boolean = any { block ->
    when (block) {
        is EditorBlock.Text -> block.textFieldState.composition != null
        is EditorBlock.Callout -> block.titleState.composition != null ||
            block.bodyBlocks.hasActiveImeComposition()
        is EditorBlock.Code -> block.codeState.composition != null
        is EditorBlock.Table -> block.headerStates.any { it.composition != null } ||
            block.rowStates.any { row -> row.any { it.composition != null } }
        is EditorBlock.HorizontalRule,
        is EditorBlock.Embed -> false
    }
}

internal data class EditorViewportAnchor(
    val blockId: String?,
    val target: EditorViewportAnchorTarget,
    val fallbackIndex: Int,
    val scrollOffset: Int,
)

internal enum class EditorViewportAnchorTarget {
    Block,
    TrailingInput,
    BottomPadding,
}

internal fun captureEditorViewportAnchor(
    blocks: List<EditorBlock>,
    firstVisibleItemIndex: Int,
    scrollOffset: Int,
): EditorViewportAnchor {
    val target = when {
        firstVisibleItemIndex < blocks.size -> EditorViewportAnchorTarget.Block
        shouldShowTrailingTextInput(blocks) && firstVisibleItemIndex == blocks.size ->
            EditorViewportAnchorTarget.TrailingInput
        else -> EditorViewportAnchorTarget.BottomPadding
    }
    return EditorViewportAnchor(
        blockId = blocks.getOrNull(firstVisibleItemIndex)?.id,
        target = target,
        fallbackIndex = firstVisibleItemIndex,
        scrollOffset = scrollOffset,
    )
}

internal fun resolveEditorViewportIndex(
    anchor: EditorViewportAnchor,
    blocks: List<EditorBlock>,
): Int? {
    if (blocks.isEmpty()) return null
    val trailingIndex = blocks.size.takeIf { shouldShowTrailingTextInput(blocks) }
    val bottomPaddingIndex = if (!shouldExpandEmptyRootInput(blocks)) {
        blocks.size + if (trailingIndex != null) 1 else 0
    } else {
        null
    }
    return when (anchor.target) {
        EditorViewportAnchorTarget.Block -> {
            val matchingIndex = anchor.blockId?.let { id -> blocks.indexOfFirst { it.id == id } }
            matchingIndex?.takeIf { it >= 0 }
                ?: anchor.fallbackIndex.coerceIn(0, blocks.lastIndex)
        }
        EditorViewportAnchorTarget.TrailingInput -> trailingIndex
            ?: bottomPaddingIndex
            ?: blocks.lastIndex
        EditorViewportAnchorTarget.BottomPadding -> bottomPaddingIndex
            ?: trailingIndex
            ?: blocks.lastIndex
    }
}

/** 빈 본문도 실제 입력 가능한 TextField 하나를 갖도록 에디터 경계에서만 보정한다. */
internal fun parseEditorDocument(value: String): List<EditorBlock> =
    MarkdownBlockParser.parse(value).ifEmpty {
        listOf(EditorBlock.Text(textFieldState = TextFieldState("")))
    }

/**
 * Material3 테마를 자동 적용하는 블록 에디터.
 * 제거된 v1 `MarkdownTextField`의 drop-in replacement.
 */
@Composable
fun MarkdownBlockTextFieldM3(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    styleConfig: MarkdownStyleConfig = defaultMaterialBlockStyleConfig(),
    documentKey: Any = Unit,
    isActive: Boolean = true,
    completionRevision: Any = Unit,
    onOpenExternalLink: (String) -> Unit = {},
    onOpenInternalLink: (String) -> Unit = {},
    onCompleteInternalLink: (MarkdownLinkCompletionRequest) -> List<MarkdownLinkCompletionCandidate> = { emptyList() },
    onCreateBlockReference: suspend (MarkdownBlockReferenceCreation, () -> Boolean) -> String? = { _, _ -> null },
    onBlockReferenceError: (String) -> Unit = {},
    isInternalLinkResolved: (String) -> Boolean = { true },
    onResolveEmbed: suspend (String) -> MarkdownEmbedPreview = {
        MarkdownEmbedPreview.Unavailable("임베드 대상을 찾을 수 없습니다.")
    },
    navigationTarget: MarkdownNavigationTarget? = null,
    onNavigationHandled: (Long) -> Unit = {},
    showQuickBar: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    MarkdownBlockTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = MarkdownEditorStyleTokens.bodyFontSize,
            lineHeight = MarkdownEditorStyleTokens.bodyLineHeight,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        styleConfig = styleConfig,
        documentKey = documentKey,
        isActive = isActive,
        completionRevision = completionRevision,
        onOpenExternalLink = onOpenExternalLink,
        onOpenInternalLink = onOpenInternalLink,
        onCompleteInternalLink = onCompleteInternalLink,
        onCreateBlockReference = onCreateBlockReference,
        onBlockReferenceError = onBlockReferenceError,
        isInternalLinkResolved = isInternalLinkResolved,
        onResolveEmbed = onResolveEmbed,
        navigationTarget = navigationTarget,
        onNavigationHandled = onNavigationHandled,
        showQuickBar = showQuickBar,
        contentPadding = contentPadding,
    )
}

internal fun markdownBlockOffsetAt(blocks: List<EditorBlock>, offset: Int): Pair<Int, Int>? {
    if (offset < 0) return null
    var start = 0
    blocks.forEachIndexed { index, block ->
        val endExclusive = start + block.toMarkdown().length
        if (offset <= endExclusive) return index to (offset - start).coerceAtLeast(0)
        start = endExclusive + 1
    }
    return null
}

@Composable
private fun defaultMaterialBlockStyleConfig(): MarkdownStyleConfig {
    val scheme = MaterialTheme.colorScheme
    val semanticColors = MaterialTheme.semanticColors
    val linkColor = MaterialTheme.colorScheme.primary
    val inlineCodeAccent = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val codeBlockBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val selectionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val bodyFontFamily = MaterialTheme.typography.bodyLarge.fontFamily

    val calloutStyles = remember(scheme, semanticColors) {
        mapOf(
            "NOTE" to CalloutDecorationStyle(
                scheme.primaryContainer.copy(alpha = 0.4f), scheme.primary
            ),
            "TIP" to CalloutDecorationStyle(
                scheme.secondaryContainer.copy(alpha = 0.4f), scheme.secondary
            ),
            "IMPORTANT" to CalloutDecorationStyle(
                scheme.tertiaryContainer.copy(alpha = 0.4f), scheme.tertiary
            ),
            "WARNING" to CalloutDecorationStyle(
                scheme.tertiaryContainer.copy(alpha = 0.4f), scheme.tertiary
            ),
            "DANGER" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.4f), scheme.error
            ),
            "CAUTION" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.4f), scheme.error
            ),
            "QUESTION" to CalloutDecorationStyle(
                scheme.surfaceVariant.copy(alpha = 0.5f), scheme.onSurfaceVariant
            ),
            "SUCCESS" to CalloutDecorationStyle(
                semanticColors.successContainer.copy(alpha = 0.4f), semanticColors.success
            ),
            "DL" to CalloutDecorationStyle(
                containerColor = scheme.primaryContainer.copy(alpha = 0.4f),
                accentColor = scheme.primary,
                layout = CalloutLayout.Horizontal,
            ),
        )
    }

    return remember(
        scheme,
        linkColor,
        inlineCodeAccent,
        highlightColor,
        codeBlockBg,
        calloutStyles,
        selectionBg,
        bodyFontFamily,
    ) {
        MarkdownStyleConfig(
            bold = SpanStyle(fontWeight = FontWeight.Bold, color = scheme.primary),
            italic = SpanStyle(fontStyle = FontStyle.Italic, color = scheme.secondary),
            boldItalic = SpanStyle(
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                color = scheme.primary,
            ),
            link = SpanStyle(color = linkColor),
            unresolvedLink = SpanStyle(color = linkColor.copy(alpha = 0.45f)),
            highlight = SpanStyle(background = highlightColor),
            inlineCode = com.ninetag.machum.markdown.service.InlineCodeStyle(
                text = SpanStyle(
                    fontFamily = bodyFontFamily,
                    fontSize = MarkdownEditorStyleTokens.inlineCodeFontSize,
                    color = inlineCodeAccent,
                ),
                borderColor = inlineCodeAccent,
            ),
            h1 = SpanStyle(
                fontSize = MarkdownEditorStyleTokens.heading1FontSize,
                fontWeight = FontWeight.Bold,
                color = markdownHeadingColor(scheme.primary, scheme.onSurface, 1),
            ),
            h2 = SpanStyle(
                fontSize = MarkdownEditorStyleTokens.heading2FontSize,
                fontWeight = FontWeight.Bold,
                color = markdownHeadingColor(scheme.primary, scheme.onSurface, 2),
            ),
            h3 = SpanStyle(
                fontSize = MarkdownEditorStyleTokens.heading3FontSize,
                fontWeight = FontWeight.Bold,
                color = markdownHeadingColor(scheme.primary, scheme.onSurface, 3),
            ),
            h4 = SpanStyle(
                fontSize = MarkdownEditorStyleTokens.heading4FontSize,
                fontWeight = FontWeight.Bold,
                color = markdownHeadingColor(scheme.primary, scheme.onSurface, 4),
            ),
            h5 = SpanStyle(
                fontSize = MarkdownEditorStyleTokens.heading5FontSize,
                fontWeight = FontWeight.Bold,
                color = markdownHeadingColor(scheme.primary, scheme.onSurface, 5),
            ),
            h6 = SpanStyle(
                fontSize = MarkdownEditorStyleTokens.heading6FontSize,
                fontWeight = FontWeight.Bold,
                color = markdownHeadingColor(scheme.primary, scheme.onSurface, 6),
            ),
            codeBlock = com.ninetag.machum.markdown.service.CodeBlockStyle(
                backgroundColor = codeBlockBg,
            ),
            bulletPrefix = SpanStyle(color = scheme.onSurfaceVariant),
            orderedPrefix = SpanStyle(
                color = scheme.onSurfaceVariant,
                fontFeatureSettings = MarkdownEditorStyleTokens.orderedListFontFeatures,
            ),
            blockquoteAccent = scheme.outline,
            horizontalRuleColor = scheme.outline,
            calloutStyles = calloutStyles,
            selectionAccent = selectionBg,
        )
    }
}
