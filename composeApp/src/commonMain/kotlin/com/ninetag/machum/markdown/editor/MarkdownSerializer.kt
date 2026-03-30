package com.ninetag.machum.markdown.editor

/**
 * clean text + spans → 마크다운 문자열 직렬화.
 * 저장 및 onValueChange 콜백에 사용한다.
 *
 * 규칙:
 * - 인라인 (Bold/Italic/…): 양쪽에 구분자 삽입
 * - Heading: 줄 앞에 prefix 삽입 ("# ", "## " 등)
 * - BulletList / OrderedList / Blockquote: prefix 가 이미 cleanText에 있으므로 그대로
 * - WikiLink / ExternalLink: 구분자가 이미 cleanText에 있으므로 그대로
 *
 * 삽입은 높은 offset 부터 처리하여 앞쪽 offset 이 영향받지 않도록 한다.
 * 같은 offset 에서는 종료 구분자(isEnd=true) 를 시작 구분자보다 먼저 삽입한다.
 */
internal object MarkdownSerializer {

    fun toMarkdown(cleanText: String, spans: List<MarkupStyleRange>): String {
        if (spans.isEmpty()) return cleanText

        data class Insertion(val offset: Int, val text: String, val isEnd: Boolean)

        val insertions = mutableListOf<Insertion>()

        for (span in spans) {
            val s = span.start.coerceIn(0, cleanText.length)
            val e = span.end.coerceIn(s, cleanText.length)

            when (span.style) {
                MarkupStyle.Bold -> {
                    insertions += Insertion(s, "**", false)
                    insertions += Insertion(e, "**", true)
                }
                MarkupStyle.Italic -> {
                    insertions += Insertion(s, "*", false)
                    insertions += Insertion(e, "*", true)
                }
                MarkupStyle.Strikethrough -> {
                    insertions += Insertion(s, "~~", false)
                    insertions += Insertion(e, "~~", true)
                }
                MarkupStyle.Highlight -> {
                    insertions += Insertion(s, "==", false)
                    insertions += Insertion(e, "==", true)
                }
                MarkupStyle.InlineCode -> {
                    insertions += Insertion(s, "`", false)
                    insertions += Insertion(e, "`", true)
                }
                MarkupStyle.H1 -> insertions += Insertion(s, "# ", false)
                MarkupStyle.H2 -> insertions += Insertion(s, "## ", false)
                MarkupStyle.H3 -> insertions += Insertion(s, "### ", false)
                MarkupStyle.H4 -> insertions += Insertion(s, "#### ", false)
                MarkupStyle.H5 -> insertions += Insertion(s, "##### ", false)
                MarkupStyle.H6 -> insertions += Insertion(s, "###### ", false)
                // BulletList / OrderedList / Blockquote / WikiLink / ExternalLink:
                // 구분자가 cleanText 에 이미 포함되어 있으므로 추가 삽입 없음
                else -> {}
            }
        }

        // 높은 offset 부터 처리 (앞쪽 offset 불변 유지).
        // 같은 offset 에서는 종료 구분자(isEnd=true)를 먼저 삽입하여 올바른 중첩 순서 유지.
        insertions.sortWith(
            compareByDescending<Insertion> { it.offset }
                .thenByDescending { it.isEnd }
        )

        val sb = StringBuilder(cleanText)
        for (ins in insertions) {
            val pos = ins.offset.coerceIn(0, sb.length)
            sb.insert(pos, ins.text)
        }
        return sb.toString()
    }
}
