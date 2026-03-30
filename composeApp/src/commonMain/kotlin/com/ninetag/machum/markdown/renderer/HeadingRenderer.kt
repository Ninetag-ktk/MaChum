package com.ninetag.machum.markdown.renderer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.token.MarkdownBlock

@Composable
fun HeadingRenderer(block: MarkdownBlock.Heading, modifier: Modifier = Modifier) {
    val style = when (block.level) {
        1    -> MaterialTheme.typography.headlineLarge
        2    -> MaterialTheme.typography.headlineMedium
        3    -> MaterialTheme.typography.headlineSmall
        4    -> MaterialTheme.typography.titleLarge
        5    -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }.copy(fontWeight = FontWeight.Bold)

    Text(
        text = buildInlineAnnotatedString(block.inlines),
        style = style,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
