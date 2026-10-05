package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.ui.editorQuickBarTarget

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.CalloutLayout
import com.ninetag.machum.markdown.state.CalloutBodyAction
import com.ninetag.machum.markdown.state.CalloutBodyBoundary
import com.ninetag.machum.markdown.state.CalloutBodyLayout
import com.ninetag.machum.markdown.state.CalloutBodyPolicy
import com.ninetag.machum.markdown.state.CalloutBottomEntryTarget
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.CursorHint
import com.ninetag.machum.markdown.ui.BlockNavigation
import com.ninetag.machum.markdown.ui.MarkdownBlockEditor
import com.ninetag.machum.markdown.ui.selection.resetDocumentSelectionOnFocus

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds

/**
 * Callout 블록 에디터.
 *
 * - 일반: Column(배경+테두리) + Icon + Title + 재귀 body
 * - DL: Row(Title + Body 가로 배치)
 *
 * body는 [MarkdownBlockEditor]를 재귀 호출하여 중첩 블록을 지원한다.
 *
 * 포커스 진입:
 * - ↓ 진입 → block-level `focusRequester` = title
 * - ↑ 진입 → `onRegisterBottomEntryFR`로 등록한 FR = body 마지막 (또는 body 없으면 title)
 *   MarkdownBlockEditor가 bottomEntryFRMap에서 직접 포커스하므로 내부 redirect 불필요.
 */
@Composable
internal fun CalloutBlockEditor(
    block: EditorBlock.Callout,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
    focusRequester: FocusRequester = remember { FocusRequester() },
    navigation: BlockNavigation = BlockNavigation(),
    cursorHint: CursorHint? = null,
    cursorHintRequestId: Long? = null,
    onCursorHintApplied: (Long) -> Unit = {},
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit = {},
    onBlocksChanged: (List<EditorBlock>) -> Unit = {},
    /** body 안의 cross-selection 활성용 — body MarkdownBlockEditor 에 전달 (Step B-2c). */
    documentSelection: androidx.compose.runtime.MutableState<DocumentSelection>? = null,
    /** 이 Callout 이 속한 외부 컨테이너의 path. body 호출 시 `containerPath + block.id` 로 누적. */
    containerPath: List<String> = emptyList(),
    /** 상위 문서의 Undo/Redo가 대기 중인 재귀 body focus 요청도 함께 폐기하도록 공유하는 수명. */
    focusEpoch: Any = Unit,
) {
    val decoStyle = styleConfig.calloutDecorationStyle(block.calloutType)
    val layout = when (decoStyle.layout) {
        CalloutLayout.Vertical -> CalloutBodyLayout.Standard
        CalloutLayout.Horizontal -> CalloutBodyLayout.Dialogue
    }
    val bodyRuntime = rememberCalloutBodyRuntime(
        block = block,
        titleFocusRequester = focusRequester,
        navigation = navigation,
        onRegisterBottomEntryFR = onRegisterBottomEntryFR,
        onBlocksChanged = onBlocksChanged,
        focusEpoch = focusEpoch,
    )
    val bodyText = block.bodyBlocks.singleOrNull() as? EditorBlock.Text
    val latestBodyFocusRequest by rememberUpdatedState(cursorHintRequestId to cursorHint)
    val latestFocusEpoch by rememberUpdatedState(focusEpoch)
    val latestBodyState by rememberUpdatedState(bodyText?.textFieldState)
    LaunchedEffect(focusEpoch, cursorHintRequestId, bodyText?.textFieldState) {
        val requestId = cursorHintRequestId ?: return@LaunchedEffect
        if (cursorHint !is CursorHint.CalloutBodyEnd || bodyText == null) return@LaunchedEffect
        // Capture the epoch's callback: request IDs restart when history replaces the coordinator.
        val acknowledge = onCursorHintApplied
        fun isCurrent() = latestFocusEpoch == focusEpoch &&
            latestBodyState === bodyText.textFieldState &&
            latestBodyFocusRequest == (requestId to CursorHint.CalloutBodyEnd)
        try {
            withFrameNanos { }
            repeat(2) {
                if (!isCurrent()) return@LaunchedEffect
                val focused = runCatching { bodyRuntime.firstFocusRequester.requestFocus() }.getOrDefault(false)
                if (focused) {
                    bodyText.textFieldState.edit { selection = androidx.compose.ui.text.TextRange(length) }
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(50.milliseconds)
            }
        } finally {
            // Clear failed attachments too; the captured coordinator rejects stale IDs.
            acknowledge(requestId)
        }
    }
    var calloutFocused by remember(block.id) { mutableStateOf(false) }
    RawEditableBlock(
        focused = calloutFocused,
        onRawEdit = navigation.mutation.onDissolveSelf,
        modifier = modifier,
        buttonEndPadding = if (layout == CalloutBodyLayout.Dialogue) 8.dp else 0.dp,
    ) {
        val contentModifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { calloutFocused = it.hasFocus }
        if (layout == CalloutBodyLayout.Dialogue) {
            DialogueCallout(
                block = block,
                decoStyle = decoStyle,
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                modifier = contentModifier,
                navigation = navigation,
                titleFocusRequester = focusRequester,
                bodyRuntime = bodyRuntime,
                documentSelection = documentSelection,
                containerPath = containerPath,
                focusEpoch = focusEpoch,
            )
        } else {
            StandardCallout(
                block = block,
                decoStyle = decoStyle,
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                modifier = contentModifier,
                navigation = navigation,
                titleFocusRequester = focusRequester,
                bodyRuntime = bodyRuntime,
                documentSelection = documentSelection,
                containerPath = containerPath,
                focusEpoch = focusEpoch,
            )
        }
    }
}

private data class CalloutBodyRuntime(
    val firstFocusRequester: FocusRequester,
    val lastFocusRequester: FocusRequester?,
    val onBlocksChanged: (List<EditorBlock>) -> Unit,
    val onNestedBottomEntryRegistered: (FocusRequester?) -> Unit,
    val execute: (CalloutBodyAction) -> Boolean,
)

@Composable
private fun rememberCalloutBodyRuntime(
    block: EditorBlock.Callout,
    titleFocusRequester: FocusRequester,
    navigation: BlockNavigation,
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit,
    onBlocksChanged: (List<EditorBlock>) -> Unit,
    focusEpoch: Any,
): CalloutBodyRuntime {
    val firstFocusRequester = remember { FocusRequester() }
    val lastFocusRequester = remember { FocusRequester() }
    var nestedBottomFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }

    val bottomEntryTarget = CalloutBodyPolicy.bottomEntryTarget(
        bodyBlockCount = block.bodyBlocks.size,
        hasNestedBottom = nestedBottomFocusRequester != null,
    )
    val bottomEntryFocusRequester = when (bottomEntryTarget) {
        CalloutBottomEntryTarget.Title -> titleFocusRequester
        CalloutBottomEntryTarget.FirstBodyBlock -> firstFocusRequester
        CalloutBottomEntryTarget.LastBodyBlock -> lastFocusRequester
        CalloutBottomEntryTarget.NestedBottom -> nestedBottomFocusRequester!!
    }
    LaunchedEffect(bottomEntryFocusRequester) {
        onRegisterBottomEntryFR(bottomEntryFocusRequester)
    }

    var pendingBodyFocus by remember(focusEpoch) { mutableStateOf(0) }
    LaunchedEffect(focusEpoch, pendingBodyFocus) {
        if (pendingBodyFocus > 0) {
            kotlinx.coroutines.delay(50.milliseconds)
            try {
                firstFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    fun focusBodyStart() {
        try {
            firstFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        (block.bodyBlocks.firstOrNull() as? EditorBlock.Text)?.textFieldState?.edit {
            selection = androidx.compose.ui.text.TextRange(0)
        }
    }

    fun focusTitleEnd() {
        titleFocusRequester.requestFocus()
        block.titleState.edit {
            selection = androidx.compose.ui.text.TextRange(block.titleState.text.length)
        }
    }

    return CalloutBodyRuntime(
        firstFocusRequester = firstFocusRequester,
        lastFocusRequester = lastFocusRequester.takeIf { block.bodyBlocks.size > 1 },
        onBlocksChanged = onBlocksChanged,
        onNestedBottomEntryRegistered = { nestedBottomFocusRequester = it },
        execute = { action ->
            when (action) {
                CalloutBodyAction.CreateBody -> {
                    onBlocksChanged(listOf(EditorBlock.Text(textFieldState = TextFieldState(""))))
                    pendingBodyFocus++
                    true
                }
                CalloutBodyAction.FocusBodyStart -> {
                    focusBodyStart()
                    true
                }
                CalloutBodyAction.FocusTitleEnd -> {
                    focusTitleEnd()
                    true
                }
                CalloutBodyAction.MovePrevious -> {
                    navigation.focus.onMoveToPrevious()
                    true
                }
                CalloutBodyAction.MoveNext -> {
                    navigation.focus.onMoveToNext()
                    true
                }
                CalloutBodyAction.Ignore -> false
            }
        },
    )
}

@Composable
private fun StandardCallout(
    block: EditorBlock.Callout,
    decoStyle: com.ninetag.machum.markdown.service.CalloutDecorationStyle,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    modifier: Modifier,
    navigation: BlockNavigation,
    titleFocusRequester: FocusRequester,
    bodyRuntime: CalloutBodyRuntime,
    documentSelection: androidx.compose.runtime.MutableState<DocumentSelection>?,
    containerPath: List<String>,
    focusEpoch: Any,
) {
    // Title 키 핸들러
    val titleKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter -> {
                bodyRuntime.execute(CalloutBodyPolicy.activate(block.bodyBlocks.isNotEmpty()))
            }
            Key.Tab -> {
                // Enter 와 동일 — body 생성/이동. 명시 핸들러로 default focus traversal(SingleLine) 가로채기.
                bodyRuntime.execute(CalloutBodyPolicy.activate(block.bodyBlocks.isNotEmpty()))
            }
            Key.DirectionDown -> {
                if (event.isShiftPressed) {
                    // docs/markdown-editor.md — 항상 Callout 자체만 atomic
                    navigation.selection.onSelectSelfAsAtomic()
                } else {
                    bodyRuntime.execute(
                        CalloutBodyPolicy.titleDown(
                            layout = CalloutBodyLayout.Standard,
                            hasBody = block.bodyBlocks.isNotEmpty(),
                        ),
                    )
                }
                true
            }
            Key.DirectionUp -> {
                if (event.isShiftPressed) {
                    navigation.selection.onSelectSelfAsAtomic()
                } else {
                    navigation.focus.onMoveToPrevious()
                }
                true
            }
            Key.Backspace -> {
                // dissolve 트리거 2: Callout 자리에 raw markdown TextBlock(rawMode=true)
                val sel = block.titleState.selection
                if (sel.collapsed && sel.start == 0) {
                    navigation.mutation.onDissolveSelf()
                    true
                } else false
            }
            else -> false
        }
    }

    CalloutVisualLayout(
        type = block.calloutType,
        decoration = decoStyle,
        textStyle = textStyle,
        modifier = modifier,
        title = { titleStyle, titleModifier ->
            BasicTextField(
                state = block.titleState,
                textStyle = titleStyle,
                decorator = { innerTextField ->
                    Box {
                        if (block.titleState.text.isEmpty()) {
                            BasicText(calloutDisplayTitle(block.calloutType, ""), style = titleStyle)
                        }
                        innerTextField()
                    }
                },
                modifier = titleModifier.focusRequester(titleFocusRequester)
                    .editorQuickBarTarget(block.titleState)
                    .resetDocumentSelectionOnFocus(block.id).then(titleKeyHandler),
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = cursorBrush,
            )
        },
        body = { bodyStyle, bodyModifier ->
            CalloutBodyEditor(
                block = block, layout = CalloutBodyLayout.Standard, bodyRuntime = bodyRuntime,
                modifier = bodyModifier, styleConfig = styleConfig, textStyle = bodyStyle,
                cursorBrush = cursorBrush, navigation = navigation,
                documentSelection = documentSelection, containerPath = containerPath, focusEpoch = focusEpoch,
            )
        },
    )
}

@Composable
private fun DialogueCallout(
    block: EditorBlock.Callout,
    decoStyle: com.ninetag.machum.markdown.service.CalloutDecorationStyle,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    modifier: Modifier,
    navigation: BlockNavigation,
    titleFocusRequester: FocusRequester,
    bodyRuntime: CalloutBodyRuntime,
    documentSelection: androidx.compose.runtime.MutableState<DocumentSelection>?,
    containerPath: List<String>,
    focusEpoch: Any,
) {
    val titleKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val sel = block.titleState.selection
        when (event.key) {
            Key.Enter -> {
                bodyRuntime.execute(CalloutBodyPolicy.activate(block.bodyBlocks.isNotEmpty()))
            }
            Key.Tab -> {
                // Enter 와 동일 — body 생성/이동. 명시 핸들러로 default \t 입력(MultiLine) 가로채기.
                bodyRuntime.execute(CalloutBodyPolicy.activate(block.bodyBlocks.isNotEmpty()))
            }
            Key.DirectionRight -> {
                if (event.isShiftPressed) {
                    // Shift+→ from DL title → Callout 자체만 atomic selection (docs/markdown-editor.md).
                    // DL 은 title 과 body 가 가로 배치라 →가 자연스러운 "박스 탈출" 방향
                    navigation.selection.onSelectSelfAsAtomic()
                    true
                } else {
                    bodyRuntime.execute(
                        CalloutBodyPolicy.titleRight(
                            layout = CalloutBodyLayout.Dialogue,
                            hasBody = block.bodyBlocks.isNotEmpty(),
                            isAtTitleEnd = sel.collapsed && sel.start >= block.titleState.text.length,
                        ),
                    )
                }
            }
            Key.DirectionDown -> {
                if (event.isShiftPressed) {
                    // docs/markdown-editor.md — 누적 selection은 최상위 preview handler가 소유
                    navigation.selection.onSelectSelfAsAtomic()
                } else {
                    bodyRuntime.execute(
                        CalloutBodyPolicy.titleDown(
                            layout = CalloutBodyLayout.Dialogue,
                            hasBody = block.bodyBlocks.isNotEmpty(),
                        ),
                    )
                }
                true
            }
            Key.DirectionUp -> {
                if (event.isShiftPressed) {
                    navigation.selection.onSelectSelfAsAtomic()
                } else {
                    navigation.focus.onMoveToPrevious()
                }
                true
            }
            Key.Backspace -> {
                // dissolve 트리거 2: Callout 자리에 raw markdown TextBlock(rawMode=true)
                if (sel.collapsed && sel.start == 0) {
                    navigation.mutation.onDissolveSelf()
                    true
                } else false
            }
            else -> false
        }
    }

    CalloutVisualLayout(
        type = block.calloutType,
        decoration = decoStyle,
        textStyle = textStyle,
        modifier = modifier,
        title = { titleStyle, titleModifier ->
            BasicTextField(
                state = block.titleState,
                textStyle = titleStyle,
                modifier = titleModifier.focusRequester(titleFocusRequester)
                    .editorQuickBarTarget(block.titleState)
                    .resetDocumentSelectionOnFocus(block.id).then(titleKeyHandler),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
                cursorBrush = cursorBrush,
            )
        },
        body = { bodyStyle, bodyModifier ->
            CalloutBodyEditor(
                block = block, layout = CalloutBodyLayout.Dialogue, bodyRuntime = bodyRuntime,
                modifier = bodyModifier, styleConfig = styleConfig, textStyle = bodyStyle,
                cursorBrush = cursorBrush, navigation = navigation,
                documentSelection = documentSelection, containerPath = containerPath, focusEpoch = focusEpoch,
            )
        },
    )
}

@Composable
private fun CalloutBodyEditor(
    block: EditorBlock.Callout,
    layout: CalloutBodyLayout,
    bodyRuntime: CalloutBodyRuntime,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    navigation: BlockNavigation,
    documentSelection: androidx.compose.runtime.MutableState<DocumentSelection>?,
    containerPath: List<String>,
    focusEpoch: Any,
    modifier: Modifier = Modifier,
) {
    if (block.bodyBlocks.isEmpty()) return

    MarkdownBlockEditor(
        blocks = block.bodyBlocks,
        onBlocksChanged = bodyRuntime.onBlocksChanged,
        modifier = modifier,
        styleConfig = styleConfig,
        textStyle = textStyle,
        cursorBrush = cursorBrush,
        isNested = true,
        onEscapeToPrevious = {
            bodyRuntime.execute(CalloutBodyPolicy.exit(layout, CalloutBodyBoundary.Previous))
        },
        onEscapeToNext = {
            bodyRuntime.execute(CalloutBodyPolicy.exit(layout, CalloutBodyBoundary.Next))
        },
        onEscapeLeft = {
            bodyRuntime.execute(CalloutBodyPolicy.exit(layout, CalloutBodyBoundary.Left))
        },
        firstBlockFocusRequester = bodyRuntime.firstFocusRequester,
        lastBlockFocusRequester = bodyRuntime.lastFocusRequester,
        onLastBlockBottomEntryRegistered = bodyRuntime.onNestedBottomEntryRegistered,
        excludeCalloutTypes = if (layout == CalloutBodyLayout.Dialogue) setOf("DL") else emptySet(),
        enableEnterEscape = true,
        documentSelection = documentSelection,
        containerPath = containerPath + block.id,
        onEscapeSelectionToPrevious = { navigation.selection.onSelectSelfAsAtomic() },
        onEscapeSelectionToNext = { navigation.selection.onSelectSelfAsAtomic() },
        focusEpoch = focusEpoch,
    )
}

internal fun calloutDisplayTitle(type: String, title: String): String =
    title.ifEmpty { if (type.equals("DL", ignoreCase = true)) "" else type.uppercase() }

internal fun calloutIcon(type: String) = when (type.uppercase()) {
    "NOTE"      -> Icons.Outlined.Edit
    "TIP"       -> Icons.Outlined.CheckCircle
    "IMPORTANT" -> Icons.Outlined.Star
    "WARNING"   -> Icons.Outlined.Warning
    "DANGER"    -> Icons.Outlined.Warning
    "CAUTION"   -> Icons.Outlined.Warning
    "QUESTION"  -> Icons.AutoMirrored.Outlined.Help
    "SUCCESS"   -> Icons.Outlined.Check
    else        -> Icons.Outlined.Info
}
