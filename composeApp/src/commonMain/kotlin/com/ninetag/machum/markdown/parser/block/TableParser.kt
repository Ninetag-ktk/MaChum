package com.ninetag.machum.markdown.parser.block

import com.ninetag.machum.markdown.parser.InlineParser
import com.ninetag.machum.markdown.parser.RawBlock
import com.ninetag.machum.markdown.token.MarkdownBlock

class TableParser(private val inlineParser: InlineParser) {

    fun parse(raw: RawBlock): MarkdownBlock {
        val lines = raw.lines.filter { it.trimStart().startsWith("|") }
        if (lines.size < 2) return fallback(raw)

        val headerLine = lines[0]
        val separatorLine = lines[1]
        if (!isSeparator(separatorLine)) return fallback(raw)

        val headers = parseCells(headerLine)
        val rows = lines.drop(2).map { parseCells(it) }

        return MarkdownBlock.Table(
            headers = headers,
            rows = rows,
            blockId = raw.blockId
        )
    }

    private fun parseCells(line: String): List<List<com.ninetag.machum.markdown.token.InlineToken>> {
        return line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { inlineParser.parse(it.trim()) }
    }

    private fun isSeparator(line: String): Boolean {
        return line.trimStart().startsWith("|") && line.contains("---")
    }

    private fun fallback(raw: RawBlock): MarkdownBlock {
        return MarkdownBlock.TextBlock(
            inlines = inlineParser.parse(raw.lines.joinToString("\n")),
            blockId = raw.blockId
        )
    }
}