package com.ninetag.machum.markdown.editor

/**
 * 입력 텍스트에서 완성된 마크다운 패턴을 감지하여 clean text + spans 로 변환한다.
 * Compose 의존성 없음 — 단위 테스트 가능.
 *
 * 처리 순서: Heading(H6→H1) → 인라인(Bold/Strike/…) → 블록 노-스트립 → 링크
 * 각 함수는 항상 현재 `text` 를 기준으로 매칭하고, 매칭 목록을 먼저 수집한 뒤 수정한다.
 */
internal object MarkdownStyleProcessor {

    data class ProcessResult(
        val cleanText: String,
        val spans: List<MarkupStyleRange>,
        val cursorPosition: Int,
    )

    /**
     * [rawText] 에서 완성된 마크다운 패턴을 모두 처리한다.
     * 변환이 없으면 null 을 반환한다.
     *
     * @param rawText        현재 버퍼 텍스트 (부분적으로 raw 마크다운을 포함할 수 있음)
     * @param cursorPosition 변환 전 커서 위치
     */
    fun process(rawText: String, cursorPosition: Int): ProcessResult? {
        var text = rawText
        var cursor = cursorPosition
        val spans = mutableListOf<MarkupStyleRange>()
        var changed = false

        // ── 구분자를 제거하는 인라인 규칙 ────────────────────────────────────────
        // 매칭은 현재 text 스냅샷을 먼저 수집한 뒤 진행하여, 루프 내 text 수정이 안전하게 적용됨.
        fun applyStripping(regex: Regex, style: MarkupStyle, prefixLen: Int, suffixLen: Int) {
            val matches = regex.findAll(text).toList()
            var offset = 0
            for (match in matches) {
                val matchStart = match.range.first + offset
                val innerText  = match.groupValues[1]
                val fullLen    = match.value.length
                val newEnd     = matchStart + innerText.length

                // 커서가 매치 범위 안(시작 초과 ~ 끝 미만)에 있으면 스트리핑 건너뜀.
                // 구분자가 있는 상태로 IME 조합이 진행 중이면, buffer 를 수정하면
                // 조합 구간이 리셋되어 자모가 분리된다.
                // cursor == matchEnd 는 닫는 구분자 바로 뒤 → 패턴 완성 → 정상 처리.
                if (cursor > matchStart && cursor < matchStart + fullLen) continue

                val before = text.substring(0, matchStart)
                val after  = text.substring(matchStart + fullLen)
                text    = before + innerText + after
                changed = true

                val origCursor = cursor
                cursor = when {
                    origCursor <= matchStart                          -> origCursor
                    origCursor <= matchStart + prefixLen             -> matchStart
                    origCursor <= matchStart + fullLen - suffixLen   -> origCursor - prefixLen
                    origCursor <= matchStart + fullLen               -> matchStart + innerText.length
                    else                                             -> origCursor - (prefixLen + suffixLen)
                }

                spans.add(MarkupStyleRange(style, matchStart, newEnd))
                offset -= (prefixLen + suffixLen)
            }
        }

        // ── Heading: prefix 제거, 줄 전체에 스팬 ─────────────────────────────────
        fun applyHeading(regex: Regex, style: MarkupStyle, prefixLen: Int) {
            val matches = regex.findAll(text).toList()
            var offset = 0
            for (match in matches) {
                val matchStart = match.range.first + offset
                val innerText  = match.groupValues[1]
                val fullLen    = match.value.length

                // 커서가 이 헤딩 줄에 있으면 스트리핑 건너뜀.
                // 줄 끝(= matchEnd)에 커서가 있어도 줄을 벗어난 게 아니므로 inclusive 범위.
                // 커서가 줄을 벗어나는 순간(다음 줄로 이동 후 텍스트 입력) 정상 처리됨.
                if (cursor in matchStart..(matchStart + fullLen)) continue

                val before = text.substring(0, matchStart)
                val after  = text.substring(matchStart + fullLen)
                text    = before + innerText + after
                changed = true

                val newEnd = matchStart + innerText.length
                cursor = when {
                    cursor <= matchStart             -> cursor
                    cursor <= matchStart + prefixLen -> matchStart
                    else                             -> cursor - prefixLen
                }

                spans.add(MarkupStyleRange(style, matchStart, newEnd))
                offset -= prefixLen
            }
        }

        // H6 먼저 (더 긴 prefix → 짧은 H1 regex 가 잘못 매칭하는 것을 방지)
        applyHeading(MarkdownConstants.H6_REGEX, MarkupStyle.H6, 7)
        applyHeading(MarkdownConstants.H5_REGEX, MarkupStyle.H5, 6)
        applyHeading(MarkdownConstants.H4_REGEX, MarkupStyle.H4, 5)
        applyHeading(MarkdownConstants.H3_REGEX, MarkupStyle.H3, 4)
        applyHeading(MarkdownConstants.H2_REGEX, MarkupStyle.H2, 3)
        applyHeading(MarkdownConstants.H1_REGEX, MarkupStyle.H1, 2)

        // 긴 패턴 먼저 (Bold 는 Italic 보다 앞)
        applyStripping(MarkdownConstants.BOLD_REGEX,          MarkupStyle.Bold,          2, 2)
        applyStripping(MarkdownConstants.STRIKETHROUGH_REGEX, MarkupStyle.Strikethrough, 2, 2)
        applyStripping(MarkdownConstants.HIGHLIGHT_REGEX,     MarkupStyle.Highlight,     2, 2)
        applyStripping(MarkdownConstants.INLINE_CODE_REGEX,   MarkupStyle.InlineCode,    1, 1)
        applyStripping(MarkdownConstants.ITALIC_STAR_REGEX,   MarkupStyle.Italic,        1, 1)
        applyStripping(MarkdownConstants.ITALIC_UNDER_REGEX,  MarkupStyle.Italic,        1, 1)

        // ── 구분자를 유지하는 블록 규칙 (텍스트 변경 없이 스팬만) ────────────────
        fun applyBlockNoStrip(regex: Regex, style: MarkupStyle) {
            for (match in regex.findAll(text)) {
                val start = match.range.first
                val end   = match.range.last + 1
                if (start < end) {
                    spans.add(MarkupStyleRange(style, start, end))
                    changed = true
                }
            }
        }

        applyBlockNoStrip(MarkdownConstants.BULLET_LIST_REGEX,  MarkupStyle.BulletList)
        applyBlockNoStrip(MarkdownConstants.ORDERED_LIST_REGEX, MarkupStyle.OrderedList)
        applyBlockNoStrip(MarkdownConstants.BLOCKQUOTE_REGEX,   MarkupStyle.Blockquote)

        // ── 링크 (구분자 유지, SpanStyle만) ──────────────────────────────────────
        fun applyLinkNoStrip(regex: Regex, style: MarkupStyle) {
            for (match in regex.findAll(text)) {
                spans.add(MarkupStyleRange(style, match.range.first, match.range.last + 1))
                changed = true
            }
        }

        applyLinkNoStrip(MarkdownConstants.WIKI_LINK_REGEX,     MarkupStyle.WikiLink)
        applyLinkNoStrip(MarkdownConstants.EXTERNAL_LINK_REGEX, MarkupStyle.ExternalLink)

        if (!changed) return null

        return ProcessResult(
            cleanText = text,
            spans = SpanManager.consolidate(spans),
            cursorPosition = cursor.coerceIn(0, text.length),
        )
    }
}
