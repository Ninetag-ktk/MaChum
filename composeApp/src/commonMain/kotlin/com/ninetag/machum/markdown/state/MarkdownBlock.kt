package com.ninetag.machum.markdown.state

/**
 * 마크다운 블록 타입.
 *
 * [InlineStyleScanner]의 `computeSpans()` 에서 블록 타입별 SpanStyle 분기에 사용된다.
 * 내부 필드는 최소한으로 유지 — 에디터는 raw text를 직접 다루므로 파싱된 내용은 불필요.
 */
sealed class MarkdownBlock {
    data class Heading(val level: Int) : MarkdownBlock()
    data object TextBlock : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
    data class CodeBlock(val language: String = "") : MarkdownBlock()
    data class Table(val columnCount: Int = 0) : MarkdownBlock()
    data class Embed(val fileName: String = "") : MarkdownBlock()
}
