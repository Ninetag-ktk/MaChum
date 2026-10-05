package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.ui.block.normalizedMarkdownTextStyle
import com.ninetag.machum.markdown.ui.block.inlineLinkActionSlotStyle

import com.ninetag.machum.external.clipEntryOf
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.service.MarkdownEditorDebugOptions
import com.ninetag.machum.markdown.service.util.handleEditorKeyEvent
import com.ninetag.machum.markdown.state.EditorInputTransformation
import com.ninetag.machum.markdown.state.RawOrigin
import com.ninetag.machum.markdown.state.standaloneMarkdownEmbedTarget
import com.ninetag.machum.markdown.state.markdownEmbedSourceAtSelection
import com.ninetag.machum.markdown.ui.diagnostics.MarkdownImeDiagnostics
import com.ninetag.machum.markdown.ui.diagnostics.MonitorMarkdownImeInput
import com.ninetag.machum.markdown.state.RawMarkdownOutputTransformation
import com.ninetag.machum.markdown.state.MarkdownRenderContext
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.CursorHint
import com.ninetag.machum.markdown.state.MarkdownInteraction
import com.ninetag.machum.markdown.state.MarkdownLayoutMappingSnapshot
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionLookupKey
import com.ninetag.machum.markdown.state.markdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.markdownLinkCompletionEditingRequest
import com.ninetag.machum.markdown.state.markdownLinkCompletionLookupKey
import com.ninetag.machum.markdown.state.rawEditOffset
import com.ninetag.machum.markdown.state.MarkdownLinkRawEdit
import com.ninetag.machum.markdown.state.isOrdinaryLink
import com.ninetag.machum.markdown.state.listDepthEdit
import com.ninetag.machum.markdown.state.orderedListDeletionEdit
import com.ninetag.machum.markdown.state.markdownOperationTextRange
import com.ninetag.machum.markdown.state.markdownPresentation
import com.ninetag.machum.markdown.ui.block.RawEditOverlay
import com.ninetag.machum.markdown.ui.block.ExternalLinkIndicatorOverlay
import com.ninetag.machum.markdown.ui.selection.resetDocumentSelectionOnFocus
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelectionPointerRegistry
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelectionPointerActive
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelection
import com.ninetag.machum.markdown.ui.selection.selectionPointerTargetKey

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * 일반 텍스트 블록 에디터.
 *
 * 인라인 서식(OutputTransformation, BlockDecorationDrawer)을 적용.
 * 블록 분할 패턴(```, > [!TYPE], ---)을 감지하여 `navigation`을 통해 분리 요청.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun TextBlockEditor(
    block: EditorBlock.Text,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
    focusRequester: FocusRequester = remember { FocusRequester() },
    navigation: BlockNavigation = BlockNavigation(),
    cursorHint: CursorHint? = null,
    cursorHintRequestId: Long? = null,
    navigationRequest: EditorNavigationRequest? = null,
    onNavigationTargetPositioned: (Long, Float) -> Unit = { _, _ -> },
    onCursorHintApplied: (Long) -> Unit = {},
    /**
     * 빈 마지막 줄 + Enter → trailing \n 제거 + onMoveToNext 호출.
     * Callout body 안 TextBlock 에서만 true (CalloutBlockEditor 의 body MarkdownBlockEditor 호출 →
     * BlockItem → TextBlockEditor 체인). 외부 TextBlock 은 false (default) 로 두어 ZWSP/격하 결과물의
     * 의도치 않은 탈출 방지. docs/markdown-editor.md의 Smart Enter 정책.
    */
    escapeOnEmptyEnter: Boolean = false,
    documentSelectionRange: TextRange? = null,
    selectionContainerPath: List<String> = emptyList(),
    renderContext: MarkdownRenderContext = MarkdownRenderContext.RootText,
    deferStandaloneEmbedParsing: Boolean = false,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val historyBoundary = LocalEditorHistoryBoundary.current
    val normalizedTextStyle = remember(textStyle) { normalizedMarkdownTextStyle(textStyle) }

    val interactionHandlers = LocalMarkdownInteractionHandlers.current

    // Keep the output transformation identity through focus/raw display changes; replacing it
    // rebuilds Compose's transformed input state and restarts the native IME session.
    var isFocused by remember { mutableStateOf(false) }
    var explicitLinkRawEdit by remember(block.textFieldState) { mutableStateOf<MarkdownLinkRawEdit?>(null) }
    fun explicitRawLinkRange(): IntRange? = block.textFieldState.let { state ->
        explicitLinkRawEdit?.range(state.text.toString(), state.selection.start, state.selection.end)
    }
    val inputTransformation = remember(block.textFieldState) {
        EditorInputTransformation(block.textFieldState) { text, range ->
            explicitLinkRawEdit = MarkdownLinkRawEdit.open(text, range)
        }
    }
    DisposableEffect(block.textFieldState) {
        if (MarkdownEditorDebugOptions.logImeDiagnostics) {
            MarkdownImeDiagnostics.log("state|kind=text|phase=attach|state=${block.textFieldState.hashCode()}")
        }
        onDispose {
            if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                MarkdownImeDiagnostics.log("state|kind=text|phase=dispose|state=${block.textFieldState.hashCode()}")
            }
        }
    }
    LaunchedEffect(block.textFieldState) {
        if (MarkdownEditorDebugOptions.logImeDiagnostics) {
            snapshotFlow {
                Triple(block.textFieldState.text.length, block.textFieldState.selection, block.textFieldState.composition)
            }.collectLatest { (length, selection, composition) ->
                MarkdownImeDiagnostics.log("input|state=${block.textFieldState.hashCode()}|length=$length|selection=$selection|composition=$composition")
            }
        }
    }
    val selectionPointerActive = LocalDocumentSelectionPointerActive.current?.value == true
    val documentSelectionState = LocalDocumentSelection.current
    val hasDocumentSelection = documentSelectionState?.value is DocumentSelection.Multi
    val hasLocalSelection = isFocused && !block.textFieldState.selection.collapsed
    var settledRenderFocusedRaw by remember { mutableStateOf(false) }
    val renderFocusedRaw = shouldRenderFocusedRaw(
        isFocused = isFocused,
        selectionPointerActive = selectionPointerActive,
        rawBeforePointer = settledRenderFocusedRaw,
        hasDocumentSelection = hasDocumentSelection,
        hasLocalSelection = hasLocalSelection,
    )
    SideEffect {
        if (!selectionPointerActive) {
            when {
                hasDocumentSelection -> settledRenderFocusedRaw = false
                !hasLocalSelection -> settledRenderFocusedRaw = isFocused
            }
        }
    }
    val latestInteractionHandlers = rememberUpdatedState(interactionHandlers)
    val outputTransformation = remember(
        block.textFieldState,
        styleConfig,
        renderContext,
    ) {
        RawMarkdownOutputTransformation(
            config = styleConfig,
            context = renderContext,
            isInternalLinkResolved = { latestInteractionHandlers.value.isInternalLinkResolved(it) },
            explicitRawLinkRange = { explicitRawLinkRange() },
        )
    }
    outputTransformation.isFocused = renderFocusedRaw
    outputTransformation.isRawMode = block.rawMode
    outputTransformation.linkActionSlotStyle = inlineLinkActionSlotStyle(normalizedTextStyle, LocalDensity.current)
    outputTransformation.linkActionLineHeight = normalizedTextStyle.lineHeight
    LaunchedEffect(outputTransformation) {
        if (MarkdownEditorDebugOptions.logImeDiagnostics) {
            MarkdownImeDiagnostics.log(
                "transform|kind=text|generation=${outputTransformation.hashCode()}|" +
                    "identity=${outputTransformation.hashCode()}|raw=${block.rawMode}|renderFocused=$renderFocusedRaw",
            )
        }
    }

    var textLayoutSnapshot by remember(outputTransformation) {
        mutableStateOf<TextLayoutSnapshot?>(null)
    }
    var textLayoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val textLayoutResult = textLayoutSnapshot?.layout
    val latestTextLayoutSnapshot = rememberUpdatedState(textLayoutSnapshot)
    val cursorBringIntoViewRequester = remember { BringIntoViewRequester() }
    val fullBringIntoView = LocalEditorFullBringIntoView.current
    val latestOutputTransformation = rememberUpdatedState(outputTransformation)
    val latestTextLayout = rememberUpdatedState(textLayoutResult)
    val selectionPointerRegistry = LocalDocumentSelectionPointerRegistry.current
    val selectionPointerKey = remember(selectionContainerPath, block.id) {
        selectionPointerTargetKey(selectionContainerPath, block.id, "text")
    }
    DisposableEffect(selectionPointerRegistry, selectionPointerKey) {
        onDispose { selectionPointerRegistry?.unregister(selectionPointerKey) }
    }
    val density = LocalDensity.current
    var hoveredLink by remember { mutableStateOf<MarkdownInteraction?>(null) }
    var rawEditOverlayHovered by remember { mutableStateOf(false) }
    var editorWidthPx by remember { mutableStateOf(0) }
    var dismissedCompletion by remember { mutableStateOf<MarkdownLinkCompletionRequest?>(null) }
    var visibleCompletion by remember(block.textFieldState) {
        mutableStateOf<Pair<MarkdownLinkCompletionRequest, List<MarkdownLinkCompletionCandidate>>?>(null)
    }
    var lastCompletionLookupKey by remember(block.textFieldState) {
        mutableStateOf<MarkdownLinkCompletionLookupKey?>(null)
    }
    var lastCompletionRevision by remember(block.textFieldState) { mutableStateOf<Any?>(null) }

    val sourceText = block.textFieldState.text.toString()
    val sourceSelection = block.textFieldState.selection
    LaunchedEffect(sourceText, sourceSelection, explicitLinkRawEdit) {
        if (explicitLinkRawEdit != null && explicitRawLinkRange() == null) explicitLinkRawEdit = null
    }
    val hasImeComposition = block.textFieldState.composition != null
    val rawCompletionRequest = markdownLinkCompletionEditingRequest(sourceText, sourceSelection, explicitRawLinkRange(), block.rawMode)
    val currentCompletionRequest = rawCompletionRequest
        ?.takeUnless { it == dismissedCompletion }
    val currentCompletionLookupKey = currentCompletionRequest?.let {
        markdownLinkCompletionLookupKey(sourceText, it)
    }
    val completionProvider = interactionHandlers.completeInternalLink
    val completionRevision = LocalMarkdownCompletionRevision.current
    LaunchedEffect(
        isFocused,
        sourceText,
        sourceSelection,
        rawCompletionRequest,
        dismissedCompletion,
        completionRevision,
    ) {
        val revisionChanged = lastCompletionRevision != completionRevision
        if (revisionChanged) {
            lastCompletionRevision = completionRevision
        }
        if (dismissedCompletion != null && rawCompletionRequest != dismissedCompletion) {
            dismissedCompletion = null
            lastCompletionLookupKey = null
        } else if (!isFocused) {
            visibleCompletion = null
            lastCompletionLookupKey = null
        } else {
            if (!revisionChanged && currentCompletionLookupKey == lastCompletionLookupKey) return@LaunchedEffect
            // Wait until the text and cursor are stable before asking the index.
            // The field itself remains immediate; only the suggestion refresh is delayed.
            kotlinx.coroutines.delay(500.milliseconds)
            val completionRequest = markdownLinkCompletionEditingRequest(sourceText, sourceSelection, explicitRawLinkRange(), block.rawMode)
                ?.takeUnless { it == dismissedCompletion }
            val lookupKey = completionRequest?.let {
                markdownLinkCompletionLookupKey(sourceText, it)
            }
            if (!revisionChanged && lookupKey == lastCompletionLookupKey) return@LaunchedEffect
            val completion = completionRequest
                ?.let { it to completionProvider(it) }
            lastCompletionLookupKey = lookupKey
            if (visibleCompletion != completion) visibleCompletion = completion
        }
    }
    val visibleRequest = visibleCompletion?.first
    val visibleIsCurrent = rawCompletionRequest != null &&
        visibleRequest == rawCompletionRequest &&
        markdownLinkCompletionLookupKey(sourceText, visibleRequest) == lastCompletionLookupKey
    val activeCompletion = visibleRequest?.takeIf { visibleIsCurrent }
    val completionCandidates = if (visibleIsCurrent) visibleCompletion?.second.orEmpty() else emptyList()
    val interactiveCompletion = activeCompletion?.takeIf {
        completionCandidates.isNotEmpty() &&
            markdownLinkCompletionRequest(sourceText, sourceSelection) == it
    }
    var selectedCompletionIndex by remember(activeCompletion, completionCandidates) { mutableStateOf(0) }
    var applyingCompletion by remember(block.textFieldState) { mutableStateOf(false) }
    var completionApplyEpoch by remember(block.textFieldState) { mutableStateOf(0L) }

    fun applyCompletion(index: Int) {
        if (applyingCompletion) return
        val request = activeCompletion ?: return
        if (
            markdownLinkCompletionRequest(
                block.textFieldState.text.toString(),
                block.textFieldState.selection,
            ) != request
        ) return
        val candidate = completionCandidates.getOrNull(index) ?: return
        val applyEpoch = completionApplyEpoch
        applyingCompletion = true
        coroutineScope.launch {
            try {
                if (applyMarkdownLinkCompletion(
                        source = block.textFieldState,
                        request = request,
                        candidate = candidate,
                        handlers = interactionHandlers,
                        isEditing = { isFocused && completionApplyEpoch == applyEpoch },
                        beforeSourceEdit = { historyBoundary?.markNextChangeAtomic() },
                    )) {
                    val updated = block.textFieldState.text.toString()
                    dismissedCompletion = markdownLinkCompletionRequest(updated, block.textFieldState.selection)
                    if (explicitLinkRawEdit != null) {
                        val start = updated.lastIndexOf("[[", block.textFieldState.selection.start)
                        val end = updated.indexOf("]]", start.coerceAtLeast(0))
                        if (start >= 0 && end >= start) explicitLinkRawEdit = MarkdownLinkRawEdit.open(updated, start..end + 1)
                    }
                    visibleCompletion = null
                }
            } finally {
                applyingCompletion = false
            }
        }
    }

    val completionKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (
            event.type != KeyEventType.KeyDown ||
            block.textFieldState.composition != null ||
            interactiveCompletion == null ||
            markdownLinkCompletionRequest(block.textFieldState.text.toString(), block.textFieldState.selection) != interactiveCompletion
        ) {
            return@onPreviewKeyEvent false
        }
        when (event.key) {
            Key.DirectionDown -> {
                selectedCompletionIndex = (selectedCompletionIndex + 1) % completionCandidates.size
                true
            }
            Key.DirectionUp -> {
                selectedCompletionIndex = (selectedCompletionIndex - 1 + completionCandidates.size) % completionCandidates.size
                true
            }
            Key.Enter, Key.Tab -> {
                applyCompletion(selectedCompletionIndex)
                true
            }
            Key.Escape -> {
                completionApplyEpoch++
                dismissedCompletion = interactiveCompletion
                visibleCompletion = null
                true
            }
            else -> false
        }
    }

    suspend fun revealCursorLine(layout: TextLayoutResult, visualOffset: Int) {
        val safeOffset = visualOffset.coerceIn(0, layout.layoutInput.text.length)
        val cursor = layout.getCursorRect(safeOffset)
        val line = layout.getLineForOffset(safeOffset)
        val lineRect = Rect(
            left = cursor.left,
            top = layout.getLineTop(line),
            right = cursor.right.coerceAtLeast(cursor.left + 1f),
            bottom = layout.getLineBottom(line),
        )
        // Cross-block cursor transfers and history restores require the whole visual line.
        fullBringIntoView?.value = true
        try {
            withFrameNanos { }
            cursorBringIntoViewRequester.bringIntoView(lineRect)
        } finally {
            fullBringIntoView?.value = false
        }
    }

    suspend fun revealSelectionOffset(
        sourceOffset: Int,
        previousSnapshot: TextLayoutSnapshot? = textLayoutSnapshot,
    ) {
        val sourceText = block.textFieldState.text.toString()
        val selection = block.textFieldState.selection
        val expectedVisualText = markdownPresentation(
            text = sourceText,
            context = renderContext,
            config = styleConfig,
            isFocused = isFocused,
            isRawMode = block.rawMode,
            selectionStart = selection.start,
            selectionEnd = selection.end,
            explicitRawLinkRange = explicitRawLinkRange(),
        ).visualText
        val snapshot = previousSnapshot?.takeIf {
            it.mapping.sourceText == sourceText &&
                it.mapping.visualText == expectedVisualText &&
                it.layout.layoutInput.text.text == expectedVisualText
        } ?: snapshotFlow {
            textLayoutSnapshot?.takeIf {
                it.mapping.sourceText == sourceText &&
                    it.mapping.visualText == expectedVisualText &&
                    it.layout.layoutInput.text.text == expectedVisualText
            }
        }.filterNotNull().first()
        revealCursorLine(
            layout = snapshot.layout,
            visualOffset = snapshot.mapping.sourceToVisualOffset(sourceOffset),
        )
    }

    LaunchedEffect(navigationRequest?.id) {
        val request = navigationRequest ?: return@LaunchedEffect
        val (snapshot, coordinates) = snapshotFlow {
            val sourceText = block.textFieldState.text.toString()
            val layoutSnapshot = textLayoutSnapshot?.takeIf {
                it.mapping.sourceText == sourceText &&
                    it.mapping.visualText == it.layout.layoutInput.text.text
            }
            val layoutCoordinates = textLayoutCoordinates?.takeIf { it.isAttached }
            if (layoutSnapshot != null && layoutCoordinates != null) {
                layoutSnapshot to layoutCoordinates
            } else {
                null
            }
        }.filterNotNull().first()
        val visualOffset = snapshot.mapping.sourceToVisualOffset(request.sourceOffset)
            .coerceIn(0, snapshot.layout.layoutInput.text.length)
        val line = snapshot.layout.getLineForOffset(visualOffset)
        val targetTopInRoot = coordinates.localToRoot(
            Offset(0f, snapshot.layout.getLineTop(line)),
        ).y
        onNavigationTargetPositioned(request.id, targetTopInRoot)
    }

    var keyboardRevealRequest by remember { mutableStateOf<Pair<Long, TextRange>?>(null) }
    val keyboardCursorRevealHandler = Modifier.onPreviewKeyEvent { event ->
        if (
            event.type == KeyEventType.KeyDown &&
            interactiveCompletion == null &&
            (event.key == Key.DirectionUp ||
                event.key == Key.DirectionDown ||
                event.key == Key.DirectionLeft ||
                event.key == Key.DirectionRight)
        ) {
            keyboardRevealRequest = (keyboardRevealRequest?.first?.plus(1L) ?: 1L) to
                block.textFieldState.selection
        }
        false
    }

    LaunchedEffect(keyboardRevealRequest) {
        val (_, selectionBeforeKey) = keyboardRevealRequest ?: return@LaunchedEffect
        if (!isFocused) return@LaunchedEffect
        val selectionAfterKey = block.textFieldState.selection
        if (selectionAfterKey == selectionBeforeKey) return@LaunchedEffect
        revealSelectionOffset(selectionAfterKey.end)
    }

    // Layout-dependent focus requests stay alive until the field is focused and laid out.
    // History restores the selection itself; only its cursor rectangle is brought into view.
    LaunchedEffect(cursorHintRequestId, cursorHint) {
        val requestId = cursorHintRequestId ?: return@LaunchedEffect
        if (
            cursorHint !is CursorHint.AtX &&
            cursorHint !is CursorHint.AtOffset &&
            cursorHint !is CursorHint.RestoredSelection &&
            cursorHint !is CursorHint.Start &&
            cursorHint !is CursorHint.End
        ) {
            return@LaunchedEffect
        }
        val layoutSnapshot = snapshotFlow {
            val sourceText = block.textFieldState.text.toString()
            textLayoutSnapshot?.takeIf { snapshot ->
                isFocused &&
                    snapshot.mapping.sourceText == sourceText &&
                    snapshot.mapping.visualText == snapshot.layout.layoutInput.text.text
            }
        }
            .filterNotNull()
            .first()
        val layout = layoutSnapshot.layout
        val mapping = layoutSnapshot.mapping
        if (MarkdownEditorDebugOptions.logImeDiagnostics) {
            MarkdownImeDiagnostics.log("cursor-hint|phase=before|request=$requestId|hint=${cursorHint.let { it::class.simpleName }}|state=${block.textFieldState.hashCode()}|selection=${block.textFieldState.selection}|composition=${block.textFieldState.composition}")
        }
        try {
            when (val hint = cursorHint) {
                is CursorHint.AtX -> {
                    if (layout.lineCount > 0) {
                        val targetLine = if (hint.lastLine) layout.lineCount - 1 else 0
                        val lineTop = layout.getLineTop(targetLine)
                        val lineBottom = layout.getLineBottom(targetLine)
                        val y = (lineTop + lineBottom) / 2
                        val visualOffset = layout.getOffsetForPosition(
                            Offset(hint.x, y)
                        )
                        val offset = mapping.visualToSourceOffset(visualOffset)
                        block.textFieldState.edit {
                            selection = TextRange(offset)
                        }
                        revealSelectionOffset(offset, layoutSnapshot)
                    }
                }
                is CursorHint.AtOffset -> {
                    val sourceOffset = hint.offset.coerceIn(0, block.textFieldState.text.length)
                    block.textFieldState.edit {
                        selection = TextRange(sourceOffset)
                    }
                    revealSelectionOffset(sourceOffset, layoutSnapshot)
                }
                is CursorHint.RestoredSelection -> {
                    val sourceOffset = hint.offset.coerceIn(0, block.textFieldState.text.length)
                    block.textFieldState.edit {
                        selection = TextRange(sourceOffset)
                    }
                    revealSelectionOffset(sourceOffset, layoutSnapshot)
                }
                is CursorHint.Start,
                is CursorHint.End -> {
                    val sourceOffset = if (hint is CursorHint.Start) {
                        0
                    } else {
                        block.textFieldState.text.length
                    }
                    block.textFieldState.edit {
                        selection = TextRange(sourceOffset)
                    }
                    revealSelectionOffset(sourceOffset, layoutSnapshot)
                }
            }
        } finally {
            MarkdownImeDiagnostics.log("cursor-hint|phase=after|request=$requestId|state=${block.textFieldState.hashCode()}|selection=${block.textFieldState.selection}|composition=${block.textFieldState.composition}")
            fullBringIntoView?.value = false
            onCursorHintApplied(requestId)
        }
    }

    // 블록 분할 패턴 감지: 텍스트를 재파싱하여 블록 서식이 포함되면 분리
    // 주의: endsWith("\n\n") 자동 분리는 비활성화됨 (#16 빈 줄 TextBlock 포함과 충돌)
    // 블록 생성은 #20 Smart Enter에서 처리 예정
    //
    // dissolve 현행 정책 (docs/markdown-editor.md):
    // rawMode=true 블록은 편집 중 reparse 를 보류한다. focus-out 시점에 약간의 delay 후 reparse.
    // Embed 원문 뒤 Enter로 본문에 진입한 경우만 편집 중에도 분리하여 새 줄로 포커스를 옮긴다.
    // (key 에 block.rawMode 포함 → dissolve/자동해제로 rawMode 가 변하면 LaunchedEffect 재시작)
    LaunchedEffect(block.textFieldState, block.rawMode, deferStandaloneEmbedParsing) {
        if ((block.rawMode && block.rawOrigin != RawOrigin.EMBED) || deferStandaloneEmbedParsing) return@LaunchedEffect
        snapshotFlow {
            block.textFieldState.text.toString() to (block.textFieldState.composition != null)
        }
            .distinctUntilChanged()
            .debounce(150.milliseconds)
            .collectLatest { (_, hasComposition) ->
                if (!hasComposition && block.textFieldState.composition == null) {
                    val source = block.textFieldState.text.toString()
                    // Read the live row too: an already queued reparse may predate recomposition.
                    if (isFocused && markdownEmbedSourceAtSelection(source, block.textFieldState.selection) != null) {
                        return@collectLatest
                    }
                    val newline = source.indexOf('\n')
                    val exitsEmbed = newline >= 0 && block.textFieldState.selection.collapsed &&
                        block.textFieldState.selection.start > newline &&
                        standaloneMarkdownEmbedTarget(source.substring(0, newline)) != null
                    if (!block.rawMode || (isFocused && exitsEmbed)) {
                        if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                            MarkdownImeDiagnostics.log("reparse|reason=input|state=${block.textFieldState.hashCode()}|focused=$isFocused|standalone=${standaloneMarkdownEmbedTarget(source) != null}")
                        }
                        if (exitsEmbed && !isFocused) navigation.mutation.onReparseSilent()
                        else navigation.mutation.onReparse()
                    }
                }
            }
    }

    // ZWSP 자동 제거 (Block→Block 빈 줄 marker 의 격하):
    // ZWSP(BLANK_LINE_MARKER) 는 빈 줄 표현용 1글자 마커. 사용자가 ZWSP 블록에 입력(텍스트/Enter)하는 순간
    // 그 블록은 더 이상 빈 줄 marker가 아니라 일반 TextBlock 이므로 ZWSP 를 제거해야 한다.
    // 제거하지 않으면 ZWSP 가 줄 시작에 박혀 있어 InlineStyleScanner 의 line prefix 매칭(`# `, `> ` 등)이 깨진다.
    LaunchedEffect(block.textFieldState) {
        snapshotFlow { block.textFieldState.text.toString() }
            .filter { it.contains(EditorBlock.BLANK_LINE_MARKER) && it.length > 1 }
            .collectLatest { text ->
                val cleaned = text.replace(EditorBlock.BLANK_LINE_MARKER, "")
                block.textFieldState.edit { replace(0, length, cleaned) }
            }
    }

    // 빈 raw 블록 자동 정리 (Block 의 마커를 모두 지워서 일반 TextField 처럼 된 transient 상태):
    // rawMode=true 인 블록의 텍스트가 빈 순간 즉시 rawMode 해제 → 그냥 일반 빈 TextBlock 으로 전환.
    // (block.id/textFieldState 유지, 플래그만 false 로 변환)
    LaunchedEffect(block.rawMode) {
        if (!block.rawMode) return@LaunchedEffect
        snapshotFlow { block.textFieldState.text.toString().isEmpty() }
            .distinctUntilChanged()
            .collectLatest { isEmpty ->
                if (isEmpty) navigation.mutation.onClearRawMode()
            }
    }

    // dissolve 정책 v3: rawMode=true 인 블록이 focus-out 되면 200ms delay 후 reparse 1회.
    // - transient focus-out (블록간 이동 중 잠깐 잃었다 다시 받는 케이스) 은
    //   key=isFocused 가 LaunchedEffect 를 cancel 시켜 reparse 발동 안 함.
    // - rawMode 가드 덕분에 일반 TextBlock 의 Smart Enter / 방향키 이동에는 영향 없음.
    // - silent 변형 호출: 사용자가 이미 다른 블록으로 포커스를 옮긴 상태이므로 새 rendering
    //   블록으로 focus 를 끌어가지 않음 (사용자 포커스 위치 보존).
    // Preserve Multi-selection endpoint IDs while focus-out parsing is deferred; clearing the
    // selection changes this key and resumes the existing parse behavior.
    LaunchedEffect(block.textFieldState, isFocused, block.rawMode, deferStandaloneEmbedParsing, hasImeComposition, documentSelectionState, hasDocumentSelection) {
        if ((block.rawMode || deferStandaloneEmbedParsing) && !isFocused && !hasImeComposition && !hasDocumentSelection) {
            kotlinx.coroutines.delay(200.milliseconds)
            if (block.textFieldState.composition == null && documentSelectionState?.value !is DocumentSelection.Multi) {
                if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                    MarkdownImeDiagnostics.log("reparse|reason=focus-out|state=${block.textFieldState.hashCode()}|focused=$isFocused")
                }
                navigation.mutation.onReparseSilent()
            }
        }
    }

    // 블록 간 커서 이동 + Backspace 병합
    val blockKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (handleInternalLinkRawKey(event, block.textFieldState, isFocused, block.rawMode, explicitRawLinkRange()) { text, range ->
                explicitLinkRawEdit = MarkdownLinkRawEdit.open(text, range)
            }) return@onPreviewKeyEvent true
        val sel = block.textFieldState.selection

        when (event.key) {
            Key.Backspace -> {
                val source = block.textFieldState.text.toString()
                val newline = sel.start - 1
                val mapping = outputTransformation.currentLayoutMappingSnapshot()
                if (sel.collapsed && sel.start == 0) {
                    navigation.mutation.onMergeWithPrevious()
                    true
                } else if (sel.collapsed && block.textFieldState.composition == null &&
                    !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
                    newline > 0 && source[newline] == '\n' && mapping.sourceText == source &&
                    mapping.sourceToVisualOffset(newline - 1) == mapping.sourceToVisualOffset(newline)
                ) {
                    // A visual deletion range also includes hidden closing syntax before this newline.
                    block.textFieldState.edit {
                        replace(newline, newline + 1, "")
                        selection = TextRange(newline)
                        orderedListDeletionEdit(source, toString(), TextRange(newline, newline + 1), selection, newline)
                            ?.let { edit ->
                                replace(edit.range.first, edit.range.last + 1, edit.replacement)
                                selection = edit.selection
                            }
                    }
                    true
                } else false
            }
            // Smart Enter 블록 탈출 (#20 정책 v2):
            // 외부 TextBlock 은 미적용 (escapeOnEmptyEnter=false default). Callout body 안 TextBlock 만
            // 활성화 (CalloutBlockEditor 가 body MarkdownBlockEditor 호출 시 enableEnterEscape=true 전달).
            // 조건: 마지막 줄이 빈 상태 (lineStart == lineEnd) + Enter → trailing \n 제거 + onMoveToNext.
            // body 안 다음 블록이 있으면 그쪽으로, 마지막이면 onEscapeToNext 체인 → Callout 외부 탈출.
            Key.Enter -> {
                if (escapeOnEmptyEnter && sel.collapsed) {
                    val text = block.textFieldState.text.toString()
                    val nextNewline = text.indexOf('\n', sel.start)
                    val isLastLine = nextNewline == -1
                    val lineStart = if (sel.start == 0) 0 else text.lastIndexOf('\n', sel.start - 1) + 1
                    val lineEnd = if (isLastLine) text.length else nextNewline
                    val isCurrentLineEmpty = lineStart == lineEnd
                    if (isLastLine && isCurrentLineEmpty) {
                        if (lineStart > 0) {
                            block.textFieldState.edit {
                                replace(lineStart - 1, lineStart, "")
                            }
                        }
                        navigation.focus.onMoveToNext()
                        true
                    } else false
                } else false
            }
            Key.Tab -> {
                val edit = listDepthEdit(
                    text = block.textFieldState.text.toString(),
                    selection = sel,
                    outdent = event.isShiftPressed,
                ) ?: return@onPreviewKeyEvent false
                block.textFieldState.edit {
                    replace(edit.range.first, edit.range.last + 1, edit.replacement)
                    selection = edit.selection
                }
                true
            }
            Key.DirectionUp -> {
                // OutputTransformation으로 길이가 바뀌므로 시각 offset에서 행을 계산한 뒤 원문 offset으로 되돌린다.
                // 첫 시각 행에서만 이전 블록으로 나가고, 나머지 행은 같은 X 위치의 바로 위 행으로 직접 이동한다.
                val layout = textLayoutResult
                val visualOffset = outputTransformation.sourceToVisualOffset(sel.start)
                if (!sel.collapsed || layout == null || layout.lineCount == 0) {
                    false
                } else {
                    val currentLine = layout.getLineForOffset(visualOffset)
                    val cursorX = layout.getHorizontalPosition(visualOffset, usePrimaryDirection = true)
                    if (currentLine == 0) {
                        if (event.isShiftPressed) {
                            // Shift+↑: 블록 단위 selection 확장 (Phase 1 Step B)
                            navigation.selection.onExtendSelectionToPrevious()
                        } else {
                            navigation.focus.onMoveToPreviousWithX(cursorX)
                        }
                        true
                    } else if (!event.isShiftPressed) {
                        val targetLine = currentLine - 1
                        val targetY = (layout.getLineTop(targetLine) + layout.getLineBottom(targetLine)) / 2
                        val targetVisualOffset = layout.offsetForLineX(targetLine, cursorX, targetY)
                        val mappedSourceOffset = outputTransformation.visualToSourceOffset(targetVisualOffset)
                        val sourceText = block.textFieldState.text
                        val targetSourceOffset = if (
                            mappedSourceOffset > 0 && sourceText[mappedSourceOffset - 1] == '\n'
                        ) {
                            mappedSourceOffset - 1
                        } else {
                            mappedSourceOffset
                        }
                        block.textFieldState.edit {
                            selection = TextRange(targetSourceOffset)
                        }
                        true
                    } else false
                }
            }
            Key.DirectionDown -> {
                // 마지막 시각 행에서만 다음 블록으로 나가고, 나머지 행은 같은 X 위치의 바로 아래 행으로 이동한다.
                val layout = textLayoutResult
                val visualOffset = outputTransformation.sourceToVisualOffset(sel.start)
                if (!sel.collapsed || layout == null || layout.lineCount == 0) {
                    false
                } else {
                    val currentLine = layout.getLineForOffset(visualOffset)
                    val cursorX = layout.getHorizontalPosition(visualOffset, usePrimaryDirection = true)
                    if (currentLine == layout.lineCount - 1) {
                        if (event.isShiftPressed) {
                            // Shift+↓: 블록 단위 selection 확장 (Phase 1 Step B)
                            navigation.selection.onExtendSelectionToNext()
                        } else {
                            navigation.focus.onMoveToNextWithX(cursorX)
                        }
                        true
                    } else if (!event.isShiftPressed) {
                        val targetLine = currentLine + 1
                        val targetY = (layout.getLineTop(targetLine) + layout.getLineBottom(targetLine)) / 2
                        val targetVisualOffset = layout.offsetForLineX(targetLine, cursorX, targetY)
                        val targetSourceOffset = outputTransformation.visualToSourceOffset(targetVisualOffset)
                        block.textFieldState.edit {
                            selection = TextRange(targetSourceOffset)
                        }
                        true
                    } else false
                }
            }
            Key.DirectionLeft -> {
                if (!sel.collapsed) return@onPreviewKeyEvent false
                if (sel.start == 0) {
                    navigation.focus.onMoveLeft()
                    true
                } else {
                    false
                }
            }
            Key.DirectionRight -> {
                // ← 와 대칭. cursor 가 text 끝에 도달하면 다음 블록 시작 위치로 진입.
                // BasicTextField 기본 동작은 블록 경계에서 멈추므로 명시 핸들러 필요.
                // raw 블록의 multi-line 텍스트(예: dissolve 된 Callout 의 `> [!NOTE] ...\n> body`) 에서
                // 끝까지 도달 시 다음 블록으로 자연스럽게 넘어가야 하는 케이스를 해결.
                if (sel.collapsed && sel.start == block.textFieldState.text.length) {
                    navigation.focus.onMoveToNext()
                    true
                } else false
            }
            else -> false
        }
    }

    val linkHoverObserver = Modifier.observeMarkdownLinkHover(
        restartKey = block.textFieldState,
        markdownProvider = { latestOutputTransformation.value },
        layoutProvider = { latestTextLayout.value },
        overlayHoveredProvider = { rawEditOverlayHovered },
        onLinkHovered = { hoveredLink = it },
    )

    val sourceClipboardKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
        if (event.key != Key.C && event.key != Key.X) return@onPreviewKeyEvent false
        if (event.key == Key.X && event.isShiftPressed) return@onPreviewKeyEvent false

        val selection = block.textFieldState.selection
        if (selection.collapsed) return@onPreviewKeyEvent false
        val sourceText = block.textFieldState.text.toString()
        val sourceRange = markdownOperationTextRange(sourceText, selection)
        val markdown = sourceText.substring(sourceRange.min, sourceRange.max)
            .replace(EditorBlock.BLANK_LINE_MARKER, "")
        if (markdown.isEmpty()) return@onPreviewKeyEvent false

        coroutineScope.launch {
            val copied = runCatching {
                clipboard.setClipEntry(clipEntryOf(markdown))
            }.isSuccess
            if (
                copied &&
                event.key == Key.X &&
                block.textFieldState.text.toString() == sourceText &&
                block.textFieldState.selection == selection
            ) {
                historyBoundary?.markNextChangeAtomic()
                block.textFieldState.edit {
                    replace(sourceRange.min, sourceRange.max, "")
                    this.selection = TextRange(sourceRange.min)
                }
            }
        }
        true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { editorWidthPx = it.width }
            .then(linkHoverObserver),
    ) {
    MonitorMarkdownImeInput(block.textFieldState) {
    BasicTextField(
        state = block.textFieldState,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                textLayoutCoordinates = coordinates
                selectionPointerRegistry?.registerText(
                    key = selectionPointerKey,
                    coordinates = coordinates,
                    containerPath = selectionContainerPath,
                    blockId = block.id,
                    offsetAtLocalPosition = offset@{ position ->
                        val snapshot = latestTextLayoutSnapshot.value ?: return@offset null
                        val visualOffset = snapshot.layout.getOffsetForPosition(position)
                        snapshot.mapping
                            .visualToSourceOffset(visualOffset)
                            .coerceIn(0, block.textFieldState.text.length)
                    },
                    collapseNativeSelection = { offset ->
                        block.textFieldState.edit {
                            selection = TextRange(offset.coerceIn(0, length))
                        }
                    },
                )
            }
            .pointerInput(block.textFieldState) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) {
                        return@awaitEachGesture
                    }
                    val transformation = latestOutputTransformation.value
                    val layout = latestTextLayout.value
                    val visualOffset = layout?.getOffsetForPosition(down.position)
                    val indentSourceOffset = visualOffset
                        ?.let(transformation::displayIndentSourceOffsetAt)
                    val interaction = if (layout != null) {
                        transformation.interactionAtVisualPosition(
                            down.position,
                            layout,
                            MarkdownEditorStyleTokens.taskCheckboxSize.toPx(),
                            MarkdownEditorStyleTokens.taskCheckboxHitSlop.toPx(),
                        )
                    } else null
                    if (interaction == null) {
                        val release = waitForMarkdownPointerRelease(
                            down.id,
                            down.position,
                            viewConfiguration.touchSlop,
                        )
                        val stayedInPlace = release != null && !release.exceededTouchSlop
                        if (stayedInPlace && indentSourceOffset != null) {
                            block.textFieldState.edit {
                                selection = TextRange(indentSourceOffset)
                            }
                        }
                        return@awaitEachGesture
                    }

                    // The checkbox owns this gesture. Letting BasicTextField also handle the down
                    // moves its cursor into the task prefix, which reveals "- [ ]" after toggling.
                    if (interaction is MarkdownInteraction.TaskCheckbox || down.type == PointerType.Touch) down.consume()

                    val release = waitForMarkdownPointerRelease(
                        down.id,
                        down.position,
                        viewConfiguration.touchSlop,
                    )
                    val released = release?.change ?: return@awaitEachGesture
                    val stayedInPlace = !release.exceededTouchSlop
                    if (!stayedInPlace) return@awaitEachGesture

                    val isLongPress = down.type == PointerType.Touch &&
                        released.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                    if (isLongPress) {
                        if (interaction.isOrdinaryLink()) {
                            explicitLinkRawEdit = MarkdownLinkRawEdit.open(
                                block.textFieldState.text.toString(), interaction.syntaxRange,
                            )
                        }
                        block.textFieldState.edit {
                            selection = TextRange(interaction.rawEditOffset())
                        }
                        focusRequester.requestFocus()
                    } else {
                        when (interaction) {
                            is MarkdownInteraction.TaskCheckbox -> {
                                block.textFieldState.edit {
                                    replace(
                                        interaction.stateOffset,
                                        interaction.stateOffset + 1,
                                        if (interaction.checked) " " else "x",
                                    )
                                }
                            }
                            is MarkdownInteraction.ExternalLink -> {
                                block.textFieldState.edit {
                                    selection = TextRange(
                                        (interaction.syntaxRange.last + 1).coerceAtMost(length),
                                    )
                                }
                                latestInteractionHandlers.value.openExternalLink(interaction.url)
                            }
                            is MarkdownInteraction.InternalLink -> {
                                block.textFieldState.edit {
                                    selection = TextRange(
                                        (interaction.syntaxRange.last + 1).coerceAtMost(length),
                                    )
                                }
                                latestInteractionHandlers.value.openInternalLink(interaction.target)
                            }
                            is MarkdownInteraction.Embed -> {
                                block.textFieldState.edit {
                                    selection = TextRange(
                                        (interaction.syntaxRange.last + 1).coerceAtMost(length),
                                    )
                                }
                                latestInteractionHandlers.value.openInternalLink(interaction.target)
                            }
                        }
                    }
                }
            }
            .bringIntoViewRequester(cursorBringIntoViewRequester)
            .focusRequester(focusRequester)
            .editorQuickBarTarget(
                block.textFieldState,
                supportsLists = !block.rawMode,
                usesMarkdownPreview = !block.rawMode,
                insertDl = navigation.mutation.onInsertDlCallout,
            )
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) explicitLinkRawEdit = null
                if (isFocused != focusState.isFocused) {
                    completionApplyEpoch++
                    isFocused = focusState.isFocused
                    if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                        MarkdownImeDiagnostics.log(
                            "focus|kind=text|state=${block.textFieldState.hashCode()}|" +
                                "focused=${focusState.isFocused}",
                        )
                    }
                }
            }
            .resetDocumentSelectionOnFocus(block.id)
            .drawBehind {
                val snapshot = textLayoutSnapshot ?: return@drawBehind
                val layout = snapshot.layout
                documentSelectionRange?.let { range ->
                    val visualStart = snapshot.mapping.sourceToVisualOffset(range.min)
                        .coerceIn(0, layout.layoutInput.text.length)
                    val visualEnd = snapshot.mapping.sourceToVisualOffset(range.max)
                        .coerceIn(visualStart, layout.layoutInput.text.length)
                    if (visualStart < visualEnd) {
                        drawPath(
                            path = layout.getPathForRange(visualStart, visualEnd),
                            color = styleConfig.selectionAccent,
                        )
                    }
                }
                drawBlockDecorations(
                    layout = layout,
                    blocks = outputTransformation.blockRanges,
                    config = styleConfig,
                    scrollOffset = 0f,
                    inlineCodeRanges = outputTransformation.inlineCodeRanges,
                    rawZones = outputTransformation.currentRawZones,
                    taskCheckboxes = outputTransformation.taskCheckboxes,
                )
                if (MarkdownEditorDebugOptions.showTextLayoutGuides) {
                    val strokeWidth = MarkdownEditorDebugOptions.textBlockBoundsWidth.toPx()
                    val halfStroke = strokeWidth / 2f
                    for (line in 0 until layout.lineCount) {
                        val top = (layout.getLineTop(line) + halfStroke).coerceIn(halfStroke, size.height)
                        val bottom = (layout.getLineBottom(line) - halfStroke).coerceIn(0f, size.height - halfStroke)
                        drawLine(
                            color = MarkdownEditorDebugOptions.textBlockBoundsColor,
                            start = Offset(0f, top),
                            end = Offset(size.width, top),
                            strokeWidth = strokeWidth,
                        )
                        drawLine(
                            color = MarkdownEditorDebugOptions.textBlockBoundsColor,
                            start = Offset(0f, bottom),
                            end = Offset(size.width, bottom),
                            strokeWidth = strokeWidth,
                        )
                    }
                }
            }
            .then(sourceClipboardKeyHandler)
            .then(keyboardCursorRevealHandler)
            .then(completionKeyHandler)
            .then(blockKeyHandler)
            .onPreviewKeyEvent { handleEditorKeyEvent(it, block.textFieldState) },
        textStyle = normalizedTextStyle,
        cursorBrush = cursorBrush,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        onTextLayout = {
            it()?.let { layout ->
                val mapping = outputTransformation.currentLayoutMappingSnapshot()
                if (mapping.visualText == layout.layoutInput.text.text) {
                    outputTransformation.updateLinkActionHeights(layout, density)
                    textLayoutSnapshot = TextLayoutSnapshot(layout = layout, mapping = mapping)
                }
            }
        },
    )
        val layout = latestTextLayout.value
        if (layout != null) {
            latestOutputTransformation.value.externalLinkIndicators.forEach { indicator ->
                if (hoveredLink == indicator.interaction) return@forEach
                indicator.visualBounds(layout)?.let { bounds ->
                    ExternalLinkIndicatorOverlay(
                        slotBounds = bounds,
                        buttonSize = with(density) { bounds.height.toDp() },
                        onClick = {
                            block.textFieldState.edit {
                                selection = TextRange(
                                    (indicator.interaction.syntaxRange.last + 1).coerceAtMost(length),
                                )
                            }
                            latestInteractionHandlers.value.openExternalLink(indicator.interaction.url)
                        },
                        onHovered = { hoveredLink = indicator.interaction },
                    )
                }
            }
        }
        hoveredLink?.let { link ->
            val transformation = latestOutputTransformation.value
            val indicatorBounds = if (link is MarkdownInteraction.ExternalLink && layout != null) {
                transformation.externalLinkIndicators
                    .firstOrNull { it.interaction == link }
                    ?.visualBounds(layout)
            } else null
            val bounds = indicatorBounds ?: layout?.let { link.visualBodyEndBounds(transformation, it) }
            if (bounds != null) {
                RawEditOverlay(
                    slotBounds = bounds,
                    buttonSize = with(density) { bounds.height.toDp() },
                    horizontalGap = 0.dp,
                    onClick = {
                        explicitLinkRawEdit = MarkdownLinkRawEdit.open(
                            block.textFieldState.text.toString(), link.syntaxRange,
                        )
                        block.textFieldState.edit {
                            selection = TextRange(link.rawEditOffset())
                        }
                        focusRequester.requestFocus()
                        hoveredLink = null
                    },
                    onHoverChanged = { hovered ->
                        rawEditOverlayHovered = hovered
                        if (!hovered) hoveredLink = null
                    },
                )
            }
        }
        if (activeCompletion != null && completionCandidates.isNotEmpty() && editorWidthPx > 0) {
            val layoutSnapshot = textLayoutSnapshot
            val cursorBounds = layoutSnapshot?.let { snapshot ->
                val visualOffset = snapshot.mapping.sourceToVisualOffset(sourceSelection.start)
                snapshot.layout.getCursorRect(visualOffset)
            }
            if (cursorBounds != null) {
                MarkdownLinkCompletionDropdown(
                    candidates = completionCandidates,
                    selectedIndex = selectedCompletionIndex,
                    width = with(density) { editorWidthPx.toDp() },
                    yOffsetPx = cursorBounds.bottom.roundToInt(),
                    onSelect = ::applyCompletion,
                )
            }
        }
    }
    }
}

internal fun shouldRenderFocusedRaw(
    isFocused: Boolean,
    selectionPointerActive: Boolean,
    rawBeforePointer: Boolean,
    hasDocumentSelection: Boolean,
    hasLocalSelection: Boolean,
): Boolean {
    if (selectionPointerActive) return rawBeforePointer
    if (hasDocumentSelection) return false
    return if (isFocused && hasLocalSelection) rawBeforePointer else isFocused
}

private data class TextLayoutSnapshot(
    val layout: TextLayoutResult,
    val mapping: MarkdownLayoutMappingSnapshot,
)

private fun TextLayoutResult.offsetForLineX(line: Int, x: Float, y: Float): Int =
    getOffsetForPosition(Offset(x, y)).coerceIn(
        getLineStart(line),
        getLineEnd(line, visibleEnd = true),
    )
