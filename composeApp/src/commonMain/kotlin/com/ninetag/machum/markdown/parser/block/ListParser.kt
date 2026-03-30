package com.ninetag.machum.markdown.parser.block

import com.ninetag.machum.markdown.parser.InlineParser
import com.ninetag.machum.markdown.parser.RawBlock
import com.ninetag.machum.markdown.token.ListItem
import com.ninetag.machum.markdown.token.MarkdownBlock

class ListParser(private val inlineParser: InlineParser) {

    fun parse(raw: RawBlock, blockParser: (RawBlock) -> MarkdownBlock): MarkdownBlock {
        val items = parseItems(raw.lines, raw.startLine, blockParser)
        val firstLine = raw.lines.first().trimStart()
        return if (isBulletLine(firstLine)) {
            MarkdownBlock.BulletList(items = items, blockId = raw.blockId)
        } else {
            MarkdownBlock.OrderedList(items = items, blockId = raw.blockId)
        }
    }

    private fun parseItems(
        lines: List<String>,
        startLine: Int,
        blockParser: (RawBlock) -> MarkdownBlock
    ): List<ListItem> {
        val items = mutableListOf<ListItem>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            if (!isListLine(trimmed)) {
                i++
                continue
            }

            val indent = line.length - line.trimStart().length
            val content = trimmed.removePrefix(Regex("^([-*]|\\d+\\.)\\s").find(trimmed)?.value ?: "")
            val inlines = inlineParser.parse(content)

            // 들여쓰기된 자식 줄 수집 (스페이스 2칸 기준)
            val childLines = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size) {
                val nextLine = lines[j]
                val nextIndent = nextLine.length - nextLine.trimStart().length
                if (nextIndent >= indent + 2) {
                    childLines.add(nextLine.substring(indent + 2))
                    j++
                } else break
            }

            val children = if (childLines.isNotEmpty()) {
                val childRaw = RawBlock(lines = childLines, startLine = startLine + i + 1)
                listOf(blockParser(childRaw))
            } else emptyList()

            items.add(ListItem(inlines = inlines, children = children))
            i = j
        }

        return items
    }

    private fun isListLine(trimmed: String): Boolean {
        return isBulletLine(trimmed) || trimmed.matches(Regex("\\d+\\.\\s.*"))
    }

    private fun isBulletLine(trimmed: String): Boolean {
        return trimmed.startsWith("- ") || trimmed.startsWith("* ")
    }
}