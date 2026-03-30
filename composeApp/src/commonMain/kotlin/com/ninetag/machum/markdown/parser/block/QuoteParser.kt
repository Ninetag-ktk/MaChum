package com.ninetag.machum.markdown.parser.block

import com.ninetag.machum.markdown.parser.InlineParser
import com.ninetag.machum.markdown.parser.RawBlock
import com.ninetag.machum.markdown.token.MarkdownBlock

class QuoteParser(private val inlineParser: InlineParser) {

    fun parse(raw: RawBlock, blockParser: (RawBlock) -> MarkdownBlock): MarkdownBlock {
        // > 접두사 제거
        val strippedLines = raw.lines.map { it.trimStart().removePrefix(">").trimStart() }
        val firstLine = strippedLines.first()

        // Callout 여부 판단 — > [!type] 제목
        val calloutRegex = Regex("^\\[!(\\w+)]\\s*(.*)")
        val match = calloutRegex.find(firstLine)

        return if (match != null) {
            val type = match.groupValues[1]
            val title = match.groupValues[2]
            val bodyLines = strippedLines.drop(1)
            val bodyBlocks = splitAndParse(bodyLines, raw.startLine + 1, blockParser)
            MarkdownBlock.Callout(
                type = type,
                title = title,
                body = bodyBlocks,
                blockId = raw.blockId
            )
        } else {
            val bodyBlocks = splitAndParse(strippedLines, raw.startLine, blockParser)
            MarkdownBlock.Blockquote(
                body = bodyBlocks,
                blockId = raw.blockId
            )
        }
    }

    private fun splitAndParse(
        lines: List<String>,
        startLine: Int,
        blockParser: (RawBlock) -> MarkdownBlock
    ): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var current = mutableListOf<String>()
        var currentStart = startLine

        fun flush() {
            if (current.isNotEmpty()) {
                blocks.add(blockParser(RawBlock(lines = current.toList(), startLine = currentStart)))
                current = mutableListOf()
            }
        }

        lines.forEachIndexed { index, line ->
            if (line.isBlank()) {
                flush()
                currentStart = startLine + index + 1
            } else {
                if (current.isEmpty()) currentStart = startLine + index
                current.add(line)
            }
        }
        flush()
        return blocks
    }
}