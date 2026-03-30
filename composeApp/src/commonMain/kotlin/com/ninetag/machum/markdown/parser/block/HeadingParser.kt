package com.ninetag.machum.markdown.parser.block

import com.ninetag.machum.markdown.parser.InlineParser
import com.ninetag.machum.markdown.parser.RawBlock
import com.ninetag.machum.markdown.token.MarkdownBlock

class HeadingParser(private val inlineParser: InlineParser) {

    fun parse(raw: RawBlock): MarkdownBlock.Heading {
        val line = raw.lines.first().trimStart()
        val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
        val content = line.removePrefix("#".repeat(level)).trimStart()
        return MarkdownBlock.Heading(
            level = level,
            inlines = inlineParser.parse(content),
            blockId = raw.blockId
        )
    }
}