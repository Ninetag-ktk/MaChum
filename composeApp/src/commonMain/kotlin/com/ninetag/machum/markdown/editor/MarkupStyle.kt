package com.ninetag.machum.markdown.editor

sealed interface MarkupStyle {

    // ── 인라인 (구분자 제거 후 SpanStyle 적용) ──────────────────────────────
    data object Bold          : MarkupStyle  // **text**
    data object Italic        : MarkupStyle  // *text* / _text_
    data object Strikethrough : MarkupStyle  // ~~text~~
    data object Highlight     : MarkupStyle  // ==text==
    data object InlineCode    : MarkupStyle  // `text`

    // ── 링크 (구분자 유지, SpanStyle만 적용) ──────────────────────────────
    data object WikiLink      : MarkupStyle  // [[target]] / [[target|alias]]
    data object ExternalLink  : MarkupStyle  // [text](url)

    // ── 블록 — Heading (구분자 제거, 줄 전체에 SpanStyle) ─────────────────
    data object H1 : MarkupStyle  // # text
    data object H2 : MarkupStyle  // ## text
    data object H3 : MarkupStyle  // ### text
    data object H4 : MarkupStyle  // #### text
    data object H5 : MarkupStyle  // ##### text
    data object H6 : MarkupStyle  // ###### text

    // ── 블록 — List / Quote (구분자 유지, prefix에 별도 SpanStyle) ─────────
    data object BulletList   : MarkupStyle  // - text / * text
    data object OrderedList  : MarkupStyle  // 1. text
    data object Blockquote   : MarkupStyle  // > text
}

// ─── 분류 헬퍼 ───────────────────────────────────────────────────────────────

val MarkupStyle.isHeading: Boolean
    get() = this is MarkupStyle.H1 || this is MarkupStyle.H2 || this is MarkupStyle.H3 ||
            this is MarkupStyle.H4 || this is MarkupStyle.H5 || this is MarkupStyle.H6

/** Heading + List + Blockquote: 줄 단위로 적용되는 스타일 */
val MarkupStyle.isBlock: Boolean
    get() = isHeading || this is MarkupStyle.BulletList ||
            this is MarkupStyle.OrderedList || this is MarkupStyle.Blockquote

/** 구분자를 clean text에서 제거하는 스타일 (Bold, Italic 등 + Heading) */
val MarkupStyle.stripsDelimiters: Boolean
    get() = !isBlock || isHeading

val allHeadings: List<MarkupStyle> = listOf(
    MarkupStyle.H1, MarkupStyle.H2, MarkupStyle.H3,
    MarkupStyle.H4, MarkupStyle.H5, MarkupStyle.H6,
)
