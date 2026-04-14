package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 테이블 블록 에디터.
 *
 * 그리드 레이아웃 + 셀별 BasicTextField.
 * 구분자 줄은 표시하지 않으며 toMarkdown()에서 자동 생성.
 */
@Composable
internal fun TableBlockEditor(
    block: EditorBlock.Table,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush = SolidColor(Color.Black),
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val borderColor = styleConfig.blockquoteAccent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, shape)
    ) {
        // Header row
        TableRow(
            cells = block.headerStates,
            textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
            cursorBrush = cursorBrush,
            borderColor = borderColor,
        )
        // Data rows
        for (row in block.rowStates) {
            TableRow(
                cells = row,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                borderColor = borderColor,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<TextFieldState>,
    textStyle: TextStyle,
    cursorBrush: Brush,
    borderColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        for ((index, cellState) in cells.withIndex()) {
            BasicTextField(
                state = cellState,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (index > 0) Modifier.border(width = 0.5.dp, color = borderColor)
                        else Modifier
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
    }
}
