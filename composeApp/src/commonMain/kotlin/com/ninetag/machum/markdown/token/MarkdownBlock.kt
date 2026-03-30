package com.ninetag.machum.markdown.token

sealed class MarkdownBlock {
    data class Heading(
        val level: Int,
        val inlines: List<InlineToken>,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data class TextBlock(
        val inlines: List<InlineToken>,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data object HorizontalRule: MarkdownBlock()

    data class CodeBlock(
        val language: String,
        val content: String,
        val startLine: Int,
        val endLine: Int,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data class Callout(
        val type: String,
        val title: String,
        val body: List<MarkdownBlock>,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data class Blockquote(
        val body: List<MarkdownBlock>,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data class BulletList(
        val items: List<ListItem>,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data class OrderedList(
        val items: List<ListItem>,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data class Table(
        val headers: List<List<InlineToken>>,
        val rows: List<List<List<InlineToken>>>,
        override val blockId: String? = null
    ): MarkdownBlock(), BlockIdentifiable

    data class Embed(
        val fileName: String,
        val section: String? = null,
        val blockId: String? = null,
        val content: MarkdownBlock? = null
    ): MarkdownBlock()
}

data class ListItem(
    val inlines: List<InlineToken>,
    val children: List<MarkdownBlock> = emptyList(),
)

interface BlockIdentifiable {
    val blockId: String?
}