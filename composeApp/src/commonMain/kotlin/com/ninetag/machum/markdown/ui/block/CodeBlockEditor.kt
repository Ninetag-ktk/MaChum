package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.ui.BlockNavigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 코드 블록 에디터.
 *
 * 라운드 배경 + monospace BasicTextField.
 * 펜스 줄(```)은 표시하지 않으며 toMarkdown()에서 자동 생성.
 */
@Composable
internal fun CodeBlockEditor(
    block: EditorBlock.Code,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush = SolidColor(Color.Black),
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    navigation: BlockNavigation = BlockNavigation(),
) {
    val codeTextStyle = textStyle.merge(TextStyle(fontFamily = FontFamily.Monospace))

    val keyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val sel = block.codeState.selection
        when (event.key) {
            Key.Backspace -> {
                if (sel.collapsed && sel.start == 0 && block.codeState.text.isEmpty()) {
                    navigation.onMergeWithPrevious()
                    true
                } else false
            }
            Key.DirectionUp -> {
                if (sel.collapsed) {
                    val text = block.codeState.text.toString()
                    val isFirstLine = text.lastIndexOf('\n', (sel.start - 1).coerceAtLeast(0)) == -1
                    if (isFirstLine) { navigation.onMoveToPrevious(); true } else false
                } else false
            }
            Key.DirectionDown -> {
                if (sel.collapsed) {
                    val text = block.codeState.text.toString()
                    val isLastLine = text.indexOf('\n', sel.start) == -1
                    if (isLastLine) { navigation.onMoveToNext(); true } else false
                } else false
            }
            else -> false
        }
    }

    BasicTextField(
        state = block.codeState,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .background(styleConfig.codeBlockBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .then(keyHandler),
        textStyle = codeTextStyle,
        cursorBrush = cursorBrush,
    )
}
