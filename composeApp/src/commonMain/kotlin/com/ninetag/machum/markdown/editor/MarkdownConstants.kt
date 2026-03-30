package com.ninetag.machum.markdown.editor

internal object MarkdownConstants {

    // ── 인라인 (구분자 제거) ─────────────────────────────────────────────────
    val BOLD_REGEX          = Regex("""(?<!\*)\*\*(?!\s)(.+?)\*\*(?!\*)""")
    val ITALIC_STAR_REGEX   = Regex("""(?<!\*)\*(?!\*|\s)(.+?)(?<!\*)\*(?!\*)""")
    val ITALIC_UNDER_REGEX  = Regex("""(?<!_)_(?!_|\s)(.+?)(?<!_)_(?!_)""")
    val STRIKETHROUGH_REGEX = Regex("""~~(?!\s)(.+?)~~""")
    val HIGHLIGHT_REGEX     = Regex("""==(?!\s)(.+?)==""")
    val INLINE_CODE_REGEX   = Regex("""`(?!\s)(.+?)`""")

    // ── 링크 (구분자 유지 — SpanStyle만) ─────────────────────────────────────
    // [[target]] 또는 [[target|alias]]
    val WIKI_LINK_REGEX     = Regex("""\[\[([^\]|]+)(?:\|([^\]]+))?\]\]""")
    // [text](url)
    val EXTERNAL_LINK_REGEX = Regex("""\[([^\]]+)\]\(([^)]+)\)""")

    // ── 블록 — Heading (구분자 제거) ─────────────────────────────────────────
    val H1_REGEX = Regex("""^# (.+?)$""", RegexOption.MULTILINE)
    val H2_REGEX = Regex("""^## (.+?)$""", RegexOption.MULTILINE)
    val H3_REGEX = Regex("""^### (.+?)$""", RegexOption.MULTILINE)
    val H4_REGEX = Regex("""^#### (.+?)$""", RegexOption.MULTILINE)
    val H5_REGEX = Regex("""^##### (.+?)$""", RegexOption.MULTILINE)
    val H6_REGEX = Regex("""^###### (.+?)$""", RegexOption.MULTILINE)

    // ── 블록 — List / Quote (구분자 유지) ────────────────────────────────────
    val BULLET_LIST_REGEX  = Regex("""^[-*] (.+?)$""", RegexOption.MULTILINE)
    val ORDERED_LIST_REGEX = Regex("""^\d+\. (.+?)$""", RegexOption.MULTILINE)
    val BLOCKQUOTE_REGEX   = Regex("""^> (.+?)$""", RegexOption.MULTILINE)
}
