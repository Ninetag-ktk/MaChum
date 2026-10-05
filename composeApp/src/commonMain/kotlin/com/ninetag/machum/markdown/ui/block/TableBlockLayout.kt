package com.ninetag.machum.markdown.ui.block

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.TextUnit
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.MarkdownRenderContext
import com.ninetag.machum.markdown.state.markdownPresentation

internal fun tableHorizontalScrollBoundary(): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(x = available.x, y = 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(x = available.x, y = 0f)
}

/** Reserve both displayed text and raw markers so entering a cell never changes its width. */
internal fun measuredTableColumnWidths(
    block: EditorBlock.Table,
    config: MarkdownStyleConfig,
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
    density: Density,
    reserveLinkActions: Boolean = false,
): List<Dp> {
    val horizontalPadding = MarkdownEditorStyleTokens.tableCellHorizontalPadding * 2
    val minimumWidth = with(density) {
        (textStyle.fontSize.takeOrElse { 16.sp } * MarkdownEditorStyleTokens.tableMinimumColumnWidth.value).toDp()
    } + horizontalPadding
    return List(block.headerStates.size) { column ->
        val header = block.headerStates[column]
        val cells = listOf(header) + block.rowStates.mapNotNull { it.getOrNull(column) }
        val widest = cells.maxOfOrNull { cell ->
            val style = if (cell === header) textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)) else textStyle
            val actionLineHeight = if (reserveLinkActions) {
                with(density) { textMeasurer.measure("\u2002\u2003", style = style).size.height.toSp() }
            } else TextUnit.Unspecified
            tableCellMeasuredTexts(cell.text, config, style, density, reserveLinkActions, actionLineHeight).maxOf { measured ->
                textMeasurer.measure(measured, style = style, softWrap = false).size.width
            }
        } ?: 0
        maxOf(minimumWidth, with(density) { widest.toDp() } + horizontalPadding + 1.dp)
    }
}

internal fun tableContentWidth(columnWidths: List<Dp>): Dp =
    columnWidths.fold(0.dp) { total, width -> total + width } +
        MarkdownEditorStyleTokens.tableBorderWidth * (columnWidths.size - 1).coerceAtLeast(0)

private fun tableCellMeasuredTexts(
    source: CharSequence, config: MarkdownStyleConfig, textStyle: TextStyle, density: Density, reserveLinkActions: Boolean,
    actionLineHeight: TextUnit,
): List<AnnotatedString> {
    val visual = tableCellVisualText(source)
    val presentation = markdownPresentation(
        visual, MarkdownRenderContext.TableCell, config,
        linkActionSlotStyle = if (reserveLinkActions) inlineLinkActionSlotStyle(textStyle, density) else null,
        linkActionLineHeight = actionLineHeight,
    )
    val preview = buildAnnotatedString {
        append(presentation.visualText)
        presentation.styledRanges.forEach { styled ->
            val start = styled.range.first.coerceIn(0, length)
            val end = (styled.range.last + 1).coerceIn(start, length)
            if (start < end) addStyle(styled.style, start, end)
        }
        presentation.paragraphStyledRanges.forEach { styled ->
            val start = styled.range.first.coerceIn(0, length)
            val end = (styled.range.last + 1).coerceIn(start, length)
            if (start < end) addStyle(styled.style, start, end)
        }
    }
    return listOf(preview, AnnotatedString(visual))
}
