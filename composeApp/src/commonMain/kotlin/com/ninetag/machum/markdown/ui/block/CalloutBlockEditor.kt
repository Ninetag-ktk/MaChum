package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.ui.BlockNavigation
import com.ninetag.machum.markdown.ui.MarkdownBlockEditor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 * - ↓ 진입 → block-level [focusRequester] = title
 * - ↑ 진입 → [onRegisterBottomEntryFR]로 등록한 FR = body 마지막 (또는 body 없으면 title)
 *   MarkdownBlockEditor가 bottomEntryFRMap에서 직접 포커스하므로 내부 redirect 불필요.
 */
@Composable
internal fun CalloutBlockEditor(
    block: EditorBlock.Callout,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush = SolidColor(Color.Black),
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    navigation: BlockNavigation = BlockNavigation(),
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit = {},
    onBlocksChanged: (List<EditorBlock>) -> Unit = {},
) {
    val decoStyle = styleConfig.calloutDecorationStyle(block.calloutType)
    val shape = RoundedCornerShape(8.dp)

    if (block.calloutType.equals("DL", ignoreCase = true)) {
        DialogueCallout(block, decoStyle, styleConfig, textStyle, cursorBrush, shape, modifier, navigation, focusRequester, onRegisterBottomEntryFR, onBlocksChanged)
    } else {
        StandardCallout(block, decoStyle, styleConfig, textStyle, cursorBrush, shape, modifier, navigation, focusRequester, onRegisterBottomEntryFR, onBlocksChanged)
    }
}

@Composable
private fun StandardCallout(
    block: EditorBlock.Callout,
    decoStyle: com.ninetag.machum.markdown.service.CalloutDecorationStyle,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    shape: RoundedCornerShape,
    modifier: Modifier,
    navigation: BlockNavigation,
    titleFocusRequester: FocusRequester,
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit,
    onBlocksChanged: (List<EditorBlock>) -> Unit,
) {
    // body 첫 블록 포커스용
    val bodyFocusRequester = remember { FocusRequester() }
    // body 마지막 블록 포커스용 (body 2+블록, 마지막이 Text일 때)
    val bodyLastFocusRequester = remember { FocusRequester() }
    // 중첩 Callout이 body 마지막 블록일 때 전파된 bottomFR
    var nestedLastFR by remember { mutableStateOf<FocusRequester?>(null) }

    // ↑ 진입용 FR을 부모의 bottomEntryFRMap에 등록
    // 우선순위: nestedLastFR (중첩 Callout) > bodyFocusRequester/bodyLastFocusRequester > titleFR
    val bottomFR = when {
        block.bodyBlocks.isEmpty() -> titleFocusRequester
        nestedLastFR != null -> nestedLastFR!!
        block.bodyBlocks.size == 1 -> bodyFocusRequester
        else -> bodyLastFocusRequester
    }
    LaunchedEffect(bottomFR) { onRegisterBottomEntryFR(bottomFR) }

    // body 생성 후 지연 포커스
    var pendingBodyFocus by remember { mutableStateOf(0) }
    LaunchedEffect(pendingBodyFocus) {
        if (pendingBodyFocus > 0) {
            kotlinx.coroutines.delay(50.milliseconds)
            try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // body 첫 블록으로 포커스 + 커서를 맨 앞으로
    fun focusBodyStart() {
        try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
        (block.bodyBlocks.firstOrNull() as? EditorBlock.Text)?.textFieldState?.edit {
            selection = androidx.compose.ui.text.TextRange(0)
        }
    }

    // Title 키 핸들러
    val titleKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter -> {
                if (block.bodyBlocks.isEmpty()) {
                    onBlocksChanged(listOf(EditorBlock.Text(textFieldState = TextFieldState(""))))
                    pendingBodyFocus++
                } else {
                    focusBodyStart()
                }
                true
            }
            Key.DirectionDown -> {
                if (block.bodyBlocks.isNotEmpty()) {
                    focusBodyStart()
                } else {
                    navigation.onMoveToNext()
                }
                true
            }
            Key.DirectionUp -> {
                navigation.onMoveToPrevious()
                true
            }
            else -> false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(decoStyle.containerColor, shape)
            .border(1.dp, decoStyle.accentColor, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = calloutIcon(block.calloutType),
                contentDescription = block.calloutType,
                tint = decoStyle.accentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            BasicTextField(
                state = block.titleState,
                textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
                modifier = Modifier.weight(1f)
                    .focusRequester(titleFocusRequester)
                    .then(titleKeyHandler),
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = cursorBrush,
            )
        }

        if (block.bodyBlocks.isNotEmpty()) {
            MarkdownBlockEditor(
                blocks = block.bodyBlocks,
                onBlocksChanged = onBlocksChanged,
                styleConfig = styleConfig,
                textStyle = textStyle.merge(TextStyle(fontSize = textStyle.fontSize * 0.9f)),
                cursorBrush = cursorBrush,
                isNested = true,
                firstBlockFocusRequester = bodyFocusRequester,
                lastBlockFocusRequester = if (block.bodyBlocks.size > 1) bodyLastFocusRequester else null,
                onLastBlockBottomEntryRegistered = { fr -> nestedLastFR = fr },
                excludeCalloutTypes = if (block.calloutType.equals("DL", ignoreCase = true)) setOf("DL") else emptySet(),
                onEscapeToPrevious = {
                    titleFocusRequester.requestFocus()
                    block.titleState.edit {
                        selection = androidx.compose.ui.text.TextRange(block.titleState.text.length)
                    }
                },
                onEscapeToNext = navigation.onMoveToNext,
            )
        }
    }
}

@Composable
private fun DialogueCallout(
    block: EditorBlock.Callout,
    decoStyle: com.ninetag.machum.markdown.service.CalloutDecorationStyle,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush,
    shape: RoundedCornerShape,
    modifier: Modifier,
    navigation: BlockNavigation,
    titleFocusRequester: FocusRequester,
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit,
    onBlocksChanged: (List<EditorBlock>) -> Unit,
) {
    val bodyFocusRequester = remember { FocusRequester() }
    val bodyLastFocusRequester = remember { FocusRequester() }
    var nestedLastFR by remember { mutableStateOf<FocusRequester?>(null) }

    // ↑ 진입용 FR 등록 (중첩 Callout 체인)
    val bottomFR = when {
        block.bodyBlocks.isEmpty() -> titleFocusRequester
        nestedLastFR != null -> nestedLastFR!!
        block.bodyBlocks.size == 1 -> bodyFocusRequester
        else -> bodyLastFocusRequester
    }
    LaunchedEffect(bottomFR) { onRegisterBottomEntryFR(bottomFR) }

    // body 생성 후 지연 포커스
    var pendingBodyFocus by remember { mutableStateOf(0) }
    LaunchedEffect(pendingBodyFocus) {
        if (pendingBodyFocus > 0) {
            kotlinx.coroutines.delay(50.milliseconds)
            try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    fun focusBodyStart() {
        try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
        (block.bodyBlocks.firstOrNull() as? EditorBlock.Text)?.textFieldState?.edit {
            selection = androidx.compose.ui.text.TextRange(0)
        }
    }

    val titleKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val sel = block.titleState.selection
        when (event.key) {
            Key.Enter -> {
                if (block.bodyBlocks.isEmpty()) {
                    onBlocksChanged(listOf(EditorBlock.Text(textFieldState = TextFieldState(""))))
                    pendingBodyFocus++
                } else {
                    focusBodyStart()
                }
                true
            }
            Key.DirectionRight -> {
                if (sel.collapsed && sel.start >= block.titleState.text.length && block.bodyBlocks.isNotEmpty()) {
                    focusBodyStart()
                    true
                } else false
            }
            Key.DirectionDown -> { navigation.onMoveToNext(); true }
            Key.DirectionUp -> { navigation.onMoveToPrevious(); true }
            else -> false
        }
    }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(decoStyle.containerColor, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        BasicTextField(
            state = block.titleState,
            textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = textStyle.fontSize.value.dp * 5)
                .padding(end = 4.dp)
                .focusRequester(titleFocusRequester)
                .then(titleKeyHandler),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
            cursorBrush = cursorBrush,
        )
        if (block.bodyBlocks.isNotEmpty()) {
            MarkdownBlockEditor(
                blocks = block.bodyBlocks,
                onBlocksChanged = onBlocksChanged,
                modifier = Modifier.weight(1f),
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                isNested = true,
                onEscapeToPrevious = navigation.onMoveToPrevious,
                onEscapeToNext = navigation.onMoveToNext,
                firstBlockFocusRequester = bodyFocusRequester,
                lastBlockFocusRequester = if (block.bodyBlocks.size > 1) bodyLastFocusRequester else null,
                onLastBlockBottomEntryRegistered = { fr -> nestedLastFR = fr },
                excludeCalloutTypes = setOf("DL"),  // DL 내부에서 DL 중첩 금지
                onEscapeLeft = {
                    titleFocusRequester.requestFocus()
                    block.titleState.edit {
                        selection = androidx.compose.ui.text.TextRange(block.titleState.text.length)
                    }
                },
            )
        }
    }
}

private fun calloutIcon(type: String) = when (type.uppercase()) {
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
