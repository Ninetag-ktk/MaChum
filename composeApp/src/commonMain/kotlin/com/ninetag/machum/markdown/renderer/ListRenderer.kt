package com.ninetag.machum.markdown.renderer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.token.ListItem
import com.ninetag.machum.markdown.token.MarkdownBlock

@Composable
fun ListRenderer(
    items: List<ListItem>,
    ordered: Boolean,
    level: Int = 0,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = (level * 16).dp)) {
        items.forEachIndexed { index, item ->
            Row {
                val bullet = if (ordered) "${index + 1}." else bulletForLevel(level)
                Text(
                    text = bullet,
                    modifier = Modifier.width(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Column {
                    Text(
                        text = buildInlineAnnotatedString(item.inlines),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    item.children.forEach { child ->
                        when (child) {
                            is MarkdownBlock.BulletList  -> ListRenderer(child.items, ordered = false, level = level + 1)
                            is MarkdownBlock.OrderedList -> ListRenderer(child.items, ordered = true, level = level + 1)
                            else                         -> BlockRenderer(child)
                        }
                    }
                }
            }
        }
    }
}

private fun bulletForLevel(level: Int): String = when (level % 3) {
    0    -> "•"
    1    -> "◦"
    else -> "▸"
}
