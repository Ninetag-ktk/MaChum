package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.ui.BlockNavigation
import com.ninetag.machum.markdown.ui.selection.resetDocumentSelectionOnFocus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
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
    modifier: Modifier = Modifier,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
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
            Key.Enter -> {
                if (sel.collapsed) {
                    val text = block.codeState.text.toString()
                    val nextNewline = text.indexOf('\n', sel.start)
                    val isLastLine = nextNewline == -1
                    val lineStart = if (sel.start == 0) 0 else text.lastIndexOf('\n', sel.start - 1) + 1
                    val lineEnd = if (isLastLine) text.length else nextNewline
                    // "줄이 비어있다" = 줄의 시작과 끝이 같다.
                    // (sel.start == lineStart 만 보면 "내용 있는 줄의 맨 앞"도 빈 줄로 오판되어 빈 줄 추가가 막힘)
                    val isCurrentLineEmpty = lineStart == lineEnd
                    if (isLastLine && isCurrentLineEmpty) {
                        // 빈 마지막 줄을 생성한 trailing \n 제거
                        if (lineStart > 0) {
                            block.codeState.edit {
                                replace(lineStart - 1, lineStart, "")
                            }
                        }
                        navigation.onMoveToNext()
                        true
                    } else false
                } else false
            }
            Key.DirectionUp -> {
                if (sel.collapsed) {
                    val text = block.codeState.text.toString()
                    val isFirstLine = text.lastIndexOf('\n', (sel.start - 1).coerceAtLeast(0)) == -1
                    if (isFirstLine) {
                        if (event.isShiftPressed) {
                            // docs/markdown-editor.md — 항상 CodeBlock 자체만 atomic
                            navigation.onSelectSelfAsAtomic()
                        } else {
                            navigation.onMoveToPrevious()
                        }
                        true
                    } else false
                } else false
            }
            Key.DirectionDown -> {
                if (sel.collapsed) {
                    val text = block.codeState.text.toString()
                    val isLastLine = text.indexOf('\n', sel.start) == -1
                    if (isLastLine) {
                        if (event.isShiftPressed) {
                            navigation.onSelectSelfAsAtomic()
                        } else {
                            navigation.onMoveToNext()
                        }
                        true
                    } else false
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
            .resetDocumentSelectionOnFocus(block.id)
            .background(styleConfig.codeBlockBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .then(keyHandler),
        textStyle = codeTextStyle,
        cursorBrush = cursorBrush,
    )
}
