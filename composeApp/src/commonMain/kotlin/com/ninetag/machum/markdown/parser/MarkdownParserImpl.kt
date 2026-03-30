package com.ninetag.machum.markdown.parser

import com.ninetag.machum.markdown.parser.block.*
import com.ninetag.machum.markdown.token.MarkdownBlock

class MarkdownParserImpl : MarkdownParser {

    private val inlineParser = InlineParser()
    private val blockParser = BlockParser(
        headingParser    = HeadingParser(inlineParser),
        codeBlockParser  = CodeBlockParser(),
        quoteParser      = QuoteParser(inlineParser),
        listParser       = ListParser(inlineParser),
        tableParser      = TableParser(inlineParser),
        embedParser      = EmbedParser(),
        textBlockParser  = TextBlockParser(inlineParser)
    )
    private val blockSplitter = BlockSplitter()

    override fun parse(text: String, embedContents: Map<String, String>): ParseResult =
        parseInternal(text, embedContents, depth = 0)

    private fun parseInternal(
        text: String,
        embedContents: Map<String, String>,
        depth: Int,
    ): ParseResult {
        val rawBlocks = blockSplitter.split(text)
        val lineToBlockIndex = mutableMapOf<Int, Int>()
        val blocks = mutableListOf<MarkdownBlock>()

        rawBlocks.forEachIndexed { blockIndex, rawBlock ->
            val block = blockParser.parse(rawBlock)

            // 임베드 콘텐츠 주입 (최대 2단계)
            val resolved = if (block is MarkdownBlock.Embed
                && depth < 2
                && embedContents.containsKey(block.fileName)
            ) {
                val embedText = embedContents[block.fileName]!!
                val embedResult = parseInternal(embedText, embedContents, depth + 1)
                val content = when {
                    block.blockId != null -> embedResult.blocks.find {
                        it is MarkdownBlock && it is com.ninetag.machum.markdown.token.BlockIdentifiable &&
                                it.blockId == block.blockId
                    }
                    block.section != null -> embedResult.blocks.filterIsInstance<MarkdownBlock.Heading>()
                        .find { it.inlines.filterIsInstance<com.ninetag.machum.markdown.token.InlineToken.Text>()
                            .joinToString("") { t -> t.value } == block.section
                        }
                    else -> embedResult.blocks.firstOrNull()
                }
                block.copy(content = content)
            } else block

            blocks.add(resolved)

            // lineToBlockIndex 매핑
            for (lineOffset in rawBlock.lines.indices) {
                lineToBlockIndex[rawBlock.startLine + lineOffset] = blockIndex
            }
        }

        return ParseResult(blocks = blocks, lineToBlockIndex = lineToBlockIndex)
    }
}