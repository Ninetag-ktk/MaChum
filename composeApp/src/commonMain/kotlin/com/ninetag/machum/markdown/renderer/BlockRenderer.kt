package com.ninetag.machum.markdown.renderer

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ninetag.machum.markdown.token.MarkdownBlock

@Composable
fun BlockRenderer(block: MarkdownBlock, modifier: Modifier = Modifier) {
    when (block) {
        is MarkdownBlock.Heading        -> HeadingRenderer(block, modifier)
        is MarkdownBlock.TextBlock      -> TextBlockRenderer(block, modifier)
        is MarkdownBlock.CodeBlock      -> CodeBlockRenderer(block, modifier)
        is MarkdownBlock.Callout        -> CalloutRenderer(block, modifier)
        is MarkdownBlock.Blockquote     -> BlockquoteRenderer(block, modifier)
        is MarkdownBlock.BulletList     -> ListRenderer(block.items, ordered = false, level = 0, modifier = modifier)
        is MarkdownBlock.OrderedList    -> ListRenderer(block.items, ordered = true, level = 0, modifier = modifier)
        is MarkdownBlock.Table          -> TableRenderer(block, modifier)
        is MarkdownBlock.HorizontalRule -> HorizontalDivider(modifier)
        is MarkdownBlock.Embed          -> EmbedRenderer(block, modifier)
    }
}
