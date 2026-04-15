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
 * - DIALOGUE: Row(Title + Body 가로 배치)
 *
 * body는 [MarkdownBlockEditor]를 재귀 호출하여 중첩 블록을 지원한다.
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
    onBlocksChanged: (List<EditorBlock>) -> Unit = {},
) {
    val decoStyle = styleConfig.calloutDecorationStyle(block.calloutType)
    val shape = RoundedCornerShape(8.dp)

    if (block.calloutType.equals("DL", ignoreCase = true)) {
        // Dialogue: ↑ 진입 시 body 마지막으로. block-level focusRequester를 body 마지막에 연결.
        DialogueCallout(block, decoStyle, styleConfig, textStyle, cursorBrush, shape, modifier, navigation, focusRequester, onBlocksChanged)
    } else {
        StandardCallout(block, decoStyle, styleConfig, textStyle, cursorBrush, shape, modifier, navigation, focusRequester, onBlocksChanged)
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
    onBlocksChanged: (List<EditorBlock>) -> Unit,
) {
    val bodyFocusRequester = remember { FocusRequester() }
    // ↑ 진입 시 body 마지막 블록에 포커스하기 위한 별도 FocusRequester
    val bodyLastFocusRequester = remember { FocusRequester() }
    // title용 별도 FocusRequester (block-level은 body 마지막에 사용)
    val localTitleFocusRequester = remember { FocusRequester() }

    // body 생성 후 지연 포커스
    var pendingBodyFocus by remember { mutableStateOf(0) }
    LaunchedEffect(pendingBodyFocus) {
        if (pendingBodyFocus > 0) {
            kotlinx.coroutines.delay(50.milliseconds)
            try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // ↑ 진입(block-level focusRequester) 시 body 마지막으로 redirect
    // titleFocusRequester = block-level. body가 있으면 body 마지막에 redirect.
    LaunchedEffect(Unit) {} // placeholder — bodyLastFocusRequester는 MarkdownBlockEditor에서 관리

    // Title 키 핸들러: Enter/↓→body, ↑→이전 블록
    val titleKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter -> {
                if (block.bodyBlocks.isEmpty()) {
                    onBlocksChanged(listOf(EditorBlock.Text(textFieldState = TextFieldState(""))))
                    pendingBodyFocus++
                } else {
                    try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
                    // title→body: 커서를 body 첫 블록 맨 처음으로
                    (block.bodyBlocks.firstOrNull() as? EditorBlock.Text)?.textFieldState?.edit {
                        selection = androidx.compose.ui.text.TextRange(0)
                    }
                }
                true
            }
            Key.DirectionDown -> {
                if (block.bodyBlocks.isNotEmpty()) {
                    try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
                    (block.bodyBlocks.firstOrNull() as? EditorBlock.Text)?.textFieldState?.edit {
                        selection = androidx.compose.ui.text.TextRange(0)
                    }
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
        // Title Row: Icon + Title
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
            // body가 있으면: title은 localFR, block-level은 body 마지막에 연결
            // body가 없으면: title에 block-level 직접 연결
            val titleFR = if (block.bodyBlocks.isNotEmpty()) localTitleFocusRequester else titleFocusRequester
            BasicTextField(
                state = block.titleState,
                textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
                modifier = Modifier.weight(1f)
                    .focusRequester(titleFR)
                    .then(titleKeyHandler),
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = cursorBrush,
            )
        }

        // Body: 재귀적 블록 에디터
        if (block.bodyBlocks.isNotEmpty()) {
            MarkdownBlockEditor(
                blocks = block.bodyBlocks,
                onBlocksChanged = onBlocksChanged,
                styleConfig = styleConfig,
                textStyle = textStyle.merge(TextStyle(fontSize = textStyle.fontSize * 0.9f)),
                cursorBrush = cursorBrush,
                isNested = true,
                firstBlockFocusRequester = bodyFocusRequester,
                lastBlockFocusRequester = titleFocusRequester,  // ↑ 진입 → body 마지막 블록
                onEscapeToPrevious = {
                    localTitleFocusRequester.requestFocus()
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
    onBlocksChanged: (List<EditorBlock>) -> Unit,
) {
    val bodyFocusRequester = remember { FocusRequester() }
    val localTitleFocusRequester = remember { FocusRequester() }

    // body 생성 후 지연 포커스
    var pendingBodyFocus by remember { mutableStateOf(0) }
    LaunchedEffect(pendingBodyFocus) {
        if (pendingBodyFocus > 0) {
            kotlinx.coroutines.delay(50.milliseconds)
            try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // Dialogue: ←→로 title↔body, ↑↓는 Callout 탈출, Enter→body 생성/이동
    val titleKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val sel = block.titleState.selection
        when (event.key) {
            Key.Enter -> {
                if (block.bodyBlocks.isEmpty()) {
                    onBlocksChanged(listOf(EditorBlock.Text(textFieldState = TextFieldState(""))))
                    pendingBodyFocus++
                } else {
                    try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
                }
                true
            }
            Key.DirectionRight -> {
                if (sel.collapsed && sel.start >= block.titleState.text.length && block.bodyBlocks.isNotEmpty()) {
                    try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
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
        val titleFR = if (block.bodyBlocks.isNotEmpty()) localTitleFocusRequester else titleFocusRequester
        BasicTextField(
            state = block.titleState,
            textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = textStyle.fontSize.value.dp * 5)
                .padding(end = 4.dp)
                .focusRequester(titleFR)
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
                lastBlockFocusRequester = titleFocusRequester,  // ↑ 진입 → body 마지막
                onEscapeLeft = {
                    localTitleFocusRequester.requestFocus()
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
