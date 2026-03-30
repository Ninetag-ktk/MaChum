package com.ninetag.machum.markdown.token

sealed class InlineToken {
    data class Text(val value: String) : InlineToken()
    data class Bold(val children: List<InlineToken>) : InlineToken()
    data class Italic(val children: List<InlineToken>) : InlineToken()
    data class Strikethrough(val children: List<InlineToken>) : InlineToken()
    data class Highlight(val children: List<InlineToken>) : InlineToken()
    data class InlineCode(val value: String) : InlineToken()
    data object LineBreak : InlineToken()
    data class WikiLink(
        val target: String,
        val alias: String? = null
    ): InlineToken()
    data class EmbedLink(
        val fileName: String
    ): InlineToken()
    data class ExternalLink(
        val text: String,
        val url: String
    ): InlineToken()
}