package com.ninetag.machum.markdown.state

/**
 * 마크다운 블록 타입.
 *
 * [InlineStyleScanner]의 `computeSpans()` 에서 블록 타입별 SpanStyle 분기에 사용된다.
 * v2 블록 에디터에서 TextBlock 콘텐츠에만 적용되므로 Heading/TextBlock/HorizontalRule 만 유지.
 * Callout/Code/Table/Embed 는 각각 전용 Composable 이 처리한다.
 */
sealed class MarkdownBlock {
    data class Heading(val level: Int) : MarkdownBlock()
    data object TextBlock : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
}
