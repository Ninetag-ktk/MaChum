package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.CalloutDecorationStyle
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.EditorDocumentSnapshot
import com.ninetag.machum.markdown.state.EditorDocumentValueCoordinator
import com.ninetag.machum.markdown.state.EditorHistory
import com.ninetag.machum.markdown.state.captureEditorDocumentSnapshot
import com.ninetag.machum.markdown.state.classifyEditorHistoryTransaction
import com.ninetag.machum.markdown.state.continueTextInputAt
import com.ninetag.machum.markdown.state.restoreBlocks
import com.ninetag.machum.markdown.state.replaceSelectedText
import com.ninetag.machum.markdown.state.toMarkdown
import com.ninetag.machum.markdown.state.MarkdownBlockParser
import com.ninetag.machum.markdown.state.SelectionEndpoint
import com.ninetag.machum.markdown.ui.selection.DocumentInputFocusRequest
import com.ninetag.machum.markdown.ui.selection.DocumentSelectionInputCapture
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelection
import com.ninetag.machum.markdown.ui.selection.LocalDocumentInputFocusRequest
import com.ninetag.machum.markdown.ui.selection.documentSelectionShortcuts
import com.ninetag.machum.markdown.ui.selection.resetDocumentSelectionOnPointerPress
import com.ninetag.machum.markdown.ui.diagnostics.TrackEditorRecomposition

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ninetag.machum.theme.semanticColors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

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
) {
    TrackEditorRecomposition(scope = "document", key = diagnosticsKey)
    val initialBlocks = remember { parseEditorDocument(value) }
    var blocks by remember { mutableStateOf(initialBlocks) }
    val valueCoordinator = remember { EditorDocumentValueCoordinator(value) }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val firstBlockFocusRequester = remember { FocusRequester() }
    var firstBlockFocusRequestId by remember { mutableStateOf(0L) }
    var nextInputFocusRequestId by remember { mutableStateOf(0L) }
    var documentInputFocusRequest by remember {
        mutableStateOf<DocumentInputFocusRequest?>(null)
    }

    // Cross-block selection 상태 (Phase 1) — 최상위에서만 호이스팅. 재귀 Callout body 는 자체 미관리
    val documentSelection = remember { mutableStateOf<DocumentSelection>(DocumentSelection.None) }
    val history = remember {
        EditorHistory(
            captureEditorDocumentSnapshot(initialBlocks),
        )
    }

    fun requestDocumentInputFocus(
        endpoint: SelectionEndpoint,
        isTextReplacementHandoff: Boolean = false,
    ) {
        val requestId = ++nextInputFocusRequestId
        documentInputFocusRequest = DocumentInputFocusRequest(
            id = requestId,
            endpoint = endpoint,
            isTextReplacementHandoff = isTextReplacementHandoff,
            onFocusTransferCompleted = { completedRequestId ->
                if (documentInputFocusRequest?.id == completedRequestId) {
                    documentInputFocusRequest = null
                }
            },
        )
    }

    LaunchedEffect(firstBlockFocusRequestId) {
        if (firstBlockFocusRequestId == 0L) return@LaunchedEffect
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
            history.reset(captureEditorDocumentSnapshot(externalBlocks))
            blocks = externalBlocks
        }
    }

    // 블록 내 TextFieldState 변경 감지 → raw markdown 직렬화 → onValueChange
    // collector가 만들어진 시점의 revision을 고정해 외부 교체 전 블록의 늦은 방출을 차단한다.
    val collectorRevision = valueCoordinator.revision
    LaunchedEffect(blocks, value, collectorRevision) {
        snapshotFlow { blocks.toMarkdown() }
            .distinctUntilChanged()
            .collectLatest { markdown ->
                if (
                    valueCoordinator.acceptInternal(
                        value = markdown,
                        expectedExternalValue = value,
                        collectorRevision = collectorRevision,
                    )
                ) {
                    val nextSnapshot = captureEditorDocumentSnapshot(blocks)
                    history.record(
                        snapshot = nextSnapshot,
                        transaction = classifyEditorHistoryTransaction(
                            previous = history.current.blocks,
                            next = nextSnapshot.blocks,
                            occurredAtMillis = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                    latestOnValueChange(markdown)
                }
            }
    }

    fun restoreHistorySnapshot(snapshot: EditorDocumentSnapshot) {
        documentSelection.value = DocumentSelection.None
        blocks = snapshot.restoreBlocks()
        firstBlockFocusRequestId++
    }

    // Ctrl+A/C/Esc + 방향키 자동 해제 단축키 — Modifier 확장 헬퍼 (selection/SelectionUiHelpers.kt) 호출.
    val shortcutHandler = Modifier.documentSelectionShortcuts(
        rootBlocks = blocks,
        documentSelection = documentSelection,
        onBlocksChanged = {
            blocks = it
            firstBlockFocusRequestId++
        },
        onSelectionFocusRequested = ::requestDocumentInputFocus,
        onUndo = {
            val snapshot = history.undo() ?: return@documentSelectionShortcuts false
            restoreHistorySnapshot(snapshot)
            true
        },
        onRedo = {
            val snapshot = history.redo() ?: return@documentSelectionShortcuts false
            restoreHistorySnapshot(snapshot)
            true
        },
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

    CompositionLocalProvider(
        LocalTextSelectionColors provides unifiedSelectionColors,
        LocalDocumentSelection provides documentSelection,
        LocalDocumentInputFocusRequest provides documentInputFocusRequest,
    ) {
        Box(modifier = shortcutHandler.resetDocumentSelectionOnPointerPress(documentSelection)) {
            MarkdownBlockEditor(
                blocks = blocks,
                onBlocksChanged = { newBlocks -> blocks = newBlocks },
                modifier = modifier,
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                isNested = false,
                documentSelection = documentSelection,
                containerPath = emptyList(),
                focusEpoch = collectorRevision,
                firstBlockFocusRequester = firstBlockFocusRequester,
            )
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
) {
    MarkdownBlockTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        styleConfig = styleConfig,
        documentKey = documentKey,
    )
}

@Composable
private fun defaultMaterialBlockStyleConfig(): MarkdownStyleConfig {
    val scheme = MaterialTheme.colorScheme
    val semanticColors = MaterialTheme.semanticColors
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val codeBlockBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val selectionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

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
        )
    }

    return remember(linkColor, codeBackground, highlightColor, codeBlockBg, calloutStyles, selectionBg) {
        MarkdownStyleConfig(
            link = SpanStyle(color = linkColor),
            highlight = SpanStyle(background = highlightColor),
            codeInline = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.85.em),
            codeInlineBackground = codeBackground,
            codeBlockBackground = codeBlockBg,
            bulletPrefix = SpanStyle(color = scheme.onSurfaceVariant),
            orderedPrefix = SpanStyle(
                color = scheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            ),
            blockquoteAccent = scheme.outline,
            horizontalRuleColor = scheme.outline,
            calloutIndicator = SpanStyle(color = scheme.onSurfaceVariant.copy(alpha = 0.7f)),
            calloutStyles = calloutStyles,
            selectionAccent = selectionBg,
        )
    }
}
