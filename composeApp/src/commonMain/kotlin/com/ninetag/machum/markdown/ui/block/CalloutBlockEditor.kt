package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.ui.MarkdownBlockEditor

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

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
    onBlocksChanged: (List<EditorBlock>) -> Unit = {},
) {
    val decoStyle = styleConfig.calloutDecorationStyle(block.calloutType)
    val shape = RoundedCornerShape(8.dp)

    if (block.calloutType.equals("DIALOGUE", ignoreCase = true)) {
        DialogueCallout(block, decoStyle, styleConfig, textStyle, cursorBrush, shape, modifier)
    } else {
        StandardCallout(block, decoStyle, styleConfig, textStyle, cursorBrush, shape, modifier)
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
) {
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
            BasicTextField(
                state = block.titleState,
                textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
                modifier = Modifier.weight(1f),
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = cursorBrush,
            )
        }

        // Body: 재귀적 블록 에디터
        if (block.bodyBlocks.isNotEmpty()) {
            MarkdownBlockEditor(
                blocks = block.bodyBlocks,
                onBlocksChanged = { /* Phase 2: 블록 분할/병합 */ },
                styleConfig = styleConfig,
                textStyle = textStyle.merge(TextStyle(fontSize = textStyle.fontSize * 0.9f)),
                cursorBrush = cursorBrush,
                isNested = true,
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
) {
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
                .padding(end = 4.dp),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
            cursorBrush = cursorBrush,
        )
        if (block.bodyBlocks.isNotEmpty()) {
            MarkdownBlockEditor(
                blocks = block.bodyBlocks,
                onBlocksChanged = { /* Phase 2 */ },
                modifier = Modifier.weight(1f),
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                isNested = true,
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
