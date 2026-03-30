package com.ninetag.machum.markdown.renderer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ninetag.machum.markdown.token.MarkdownBlock

@Composable
fun TextBlockRenderer(block: MarkdownBlock.TextBlock, modifier: Modifier = Modifier) {
    Text(
        text = buildInlineAnnotatedString(block.inlines),
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.fillMaxWidth(),
    )
}
