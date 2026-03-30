package com.ninetag.machum.markdown.parser

import com.ninetag.machum.markdown.token.MarkdownBlock

interface MarkdownParser {
    fun parse(
        text: String,
        embedContents: Map<String, String> = emptyMap(),
    ): ParseResult
}

data class ParseResult(
    val blocks: List<MarkdownBlock>,
    val lineToBlockIndex: Map<Int, Int>
)

data class RawBlock(
    val lines: List<String>,
    val startLine: Int,
    val blockId: String? = null
)