package com.ninetag.machum.markdown.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.MarkdownRenderContext

@Composable
internal fun MarkdownEmbedTablePreview(
    block: EditorBlock.Table,
    config: MarkdownStyleConfig,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (block.headerStates.isEmpty()) return
    val widths = measuredTableColumnWidths(block, config, textStyle, rememberTextMeasurer(), LocalDensity.current)
    val contentWidth = tableContentWidth(widths)
    val borderWidth = MarkdownEditorStyleTokens.tableBorderWidth
    val borderColor = config.blockquoteAccent
    val scrollState = rememberScrollState()
    val horizontalScrollBoundary = remember { tableHorizontalScrollBoundary() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewportWidth = maxWidth
        Box(
            Modifier.fillMaxWidth().consumeHorizontalWheelAtBoundary(scrollState)
                .nestedScroll(horizontalScrollBoundary).horizontalScroll(scrollState),
        ) {
            Row(Modifier.width(maxOf(contentWidth, viewportWidth)), horizontalArrangement = Arrangement.Center) {
                Column(Modifier.width(contentWidth).border(borderWidth, borderColor, RoundedCornerShape(4.dp))) {
                    (listOf(block.headerStates) + block.rowStates).forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) {
                            Box(Modifier.height(borderWidth).fillMaxWidth().background(borderColor))
                        }
                        Row(Modifier.width(contentWidth).height(IntrinsicSize.Min)) {
                            widths.forEachIndexed { column, width ->
                                if (column > 0) {
                                    Box(Modifier.width(borderWidth).fillMaxHeight().background(borderColor))
                                }
                                Box(
                                    Modifier.width(width).padding(
                                        horizontal = MarkdownEditorStyleTokens.tableCellHorizontalPadding,
                                        vertical = MarkdownEditorStyleTokens.tableCellVerticalPadding,
                                    ),
                                ) {
                                    EmbedPreviewText(
                                        source = tableCellVisualText(row.getOrNull(column)?.text?.toString().orEmpty()),
                                        config = config,
                                        textStyle = if (rowIndex == 0) textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)) else textStyle,
                                        context = MarkdownRenderContext.TableCell,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
