package com.ninetag.machum.markdown.renderer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.token.InlineToken
import com.ninetag.machum.markdown.token.MarkdownBlock

@Composable
fun TableRenderer(block: MarkdownBlock.Table, modifier: Modifier = Modifier) {
    val borderColor = MaterialTheme.colorScheme.outline
    val borderWidth = 1.dp

    val allRows: List<List<List<InlineToken>>> = buildList {
        if (block.headers.isNotEmpty()) add(block.headers)
        addAll(block.rows)
    }
    val colCount = allRows.maxOfOrNull { it.size } ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor),
    ) {
        allRows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(colCount) { colIdx ->
                    val cell = row.getOrNull(colIdx) ?: emptyList()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(borderWidth, borderColor)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = buildInlineAnnotatedString(cell),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
