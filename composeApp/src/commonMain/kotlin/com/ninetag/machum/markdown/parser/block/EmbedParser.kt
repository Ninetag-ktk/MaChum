package com.ninetag.machum.markdown.parser.block

import com.ninetag.machum.markdown.parser.RawBlock
import com.ninetag.machum.markdown.token.MarkdownBlock

class EmbedParser {

    fun parse(raw: RawBlock): MarkdownBlock.Embed {
        val line = raw.lines.first().trimStart()
        val inner = line.removePrefix("![[").removeSuffix("]]")

        // # 과 ^ 중 마지막으로 등장한 구분자 기준으로 파싱
        val hashIndex = inner.lastIndexOf('#')
        val caretIndex = inner.lastIndexOf('^')

        return when {
            caretIndex > hashIndex && caretIndex != -1 -> MarkdownBlock.Embed(
                fileName = inner.substring(0, caretIndex),
                blockId = inner.substring(caretIndex + 1)
            )
            hashIndex != -1 -> MarkdownBlock.Embed(
                fileName = inner.substring(0, hashIndex),
                section = inner.substring(hashIndex + 1)
            )
            else -> MarkdownBlock.Embed(fileName = inner)
        }
    }
}