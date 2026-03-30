package com.ninetag.machum.markdown.parser.block

import com.ninetag.machum.markdown.parser.RawBlock
import com.ninetag.machum.markdown.token.MarkdownBlock

class CodeBlockParser {

    fun parse(raw: RawBlock): MarkdownBlock.CodeBlock {
        val firstLine = raw.lines.first().trimStart()
        val language = firstLine.removePrefix("```").trim()
        val content = raw.lines
            .drop(1)
            .dropLast(1)
            .joinToString("\n")
        return MarkdownBlock.CodeBlock(
            language = language,
            content = content,
            startLine = raw.startLine,
            endLine = raw.startLine + raw.lines.size - 1,
            blockId = raw.blockId
        )
    }
}