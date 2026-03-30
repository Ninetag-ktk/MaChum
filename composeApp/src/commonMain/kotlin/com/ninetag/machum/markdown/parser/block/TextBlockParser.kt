package com.ninetag.machum.markdown.parser.block

import com.ninetag.machum.markdown.parser.InlineParser
import com.ninetag.machum.markdown.parser.RawBlock
import com.ninetag.machum.markdown.token.MarkdownBlock

class TextBlockParser(private val inlineParser: InlineParser) {

    fun parse(raw: RawBlock): MarkdownBlock.TextBlock {
        val content = raw.lines.joinToString("\n")
        return MarkdownBlock.TextBlock(
            inlines = inlineParser.parse(content),
            blockId = raw.blockId
        )
    }
}