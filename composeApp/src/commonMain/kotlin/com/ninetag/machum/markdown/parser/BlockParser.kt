package com.ninetag.machum.markdown.parser

import com.ninetag.machum.markdown.parser.block.*
import com.ninetag.machum.markdown.token.MarkdownBlock

class BlockParser(
    private val headingParser: HeadingParser,
    private val codeBlockParser: CodeBlockParser,
    private val quoteParser: QuoteParser,
    private val listParser: ListParser,
    private val tableParser: TableParser,
    private val embedParser: EmbedParser,
    private val textBlockParser: TextBlockParser
) {
    fun parse(raw: RawBlock): MarkdownBlock {
        val firstLine = raw.lines.first().trimStart()
        return when {
            firstLine.startsWith("#")   -> headingParser.parse(raw)
            firstLine.startsWith("```") -> codeBlockParser.parse(raw)
            firstLine.startsWith(">")   -> quoteParser.parse(raw, ::parse)
            firstLine.startsWith("|")   -> tableParser.parse(raw)
            isListLine(firstLine)       -> listParser.parse(raw, ::parse)
            isEmbedLine(firstLine)      -> embedParser.parse(raw)
            firstLine == "---"          -> MarkdownBlock.HorizontalRule
            else                        -> textBlockParser.parse(raw)
        }
    }

    private fun isListLine(trimmed: String): Boolean {
        return trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") ||
                trimmed.matches(Regex("\\d+\\.\\s.*"))
    }

    private fun isEmbedLine(trimmed: String): Boolean {
        return trimmed.startsWith("![[") && trimmed.endsWith("]]")
    }
}