package com.ninetag.machum.markdown.state

/** Inline scanner가 분기할 때만 사용하는 경량 블록 타입. */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int) : MarkdownBlock
    data object TextBlock : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

/**
 * 비활성 블록의 raw 텍스트에서 Markdown 의미 범위를 계산한다.
 *
 * 전략: 일반 서식 마커(**, ~~, # 등)는 OutputTransformation에서 제거하고, inline code의 backtick은
 * 폭을 유지한 채 투명하게 표시한다. 링크 문법과 목록 선행 공백은 hiddenSyntax로 구분한다.
 *
 * v2 블록 에디터에서는 TextBlock 콘텐츠에만 적용. Callout/Code/Table/Embed 는
 * 각각 전용 Composable 이 처리하므로 본 스캐너는 다루지 않는다.
 */
internal object InlineStyleScanner {

    /**
     * @param block      파서가 인식한 블록 타입 (처리 전략 결정에 사용)
     * @param blockText  블록의 raw 텍스트 (blockRanges 기준)
     * @param docOffset  블록의 문서 내 시작 오프셋
     * @return 문서 내 절대 범위와 Markdown 의미 목록
     */
    fun computeSpans(
        block: MarkdownBlock,
        blockText: String,
        docOffset: Int,
    ): List<MarkdownSpan> {
        if (blockText.isEmpty()) return emptyList()
        return when (block) {
            is MarkdownBlock.HorizontalRule -> listOf(
                MarkdownSpan(docOffset until docOffset + blockText.length, MarkdownInlineRole.BlockTransparent),
            )
            is MarkdownBlock.Heading        -> headingSpans(block.level, blockText, docOffset)
            MarkdownBlock.TextBlock         -> lineScannedSpans(blockText, docOffset)
        }
    }

    /**
     * 블록 prefix가 없는 여러 줄 텍스트에서 인라인 마커를 줄을 넘어 스캔한다.
     * 연속된 일반 텍스트 줄(헤딩·코드블록·블록prefix 없는)에만 사용.
     */
    fun computeMultiLineSpans(
        blockText: String,
        docOffset: Int,
    ): List<MarkdownSpan> {
        if (blockText.isEmpty()) return emptyList()
        val spans = mutableListOf<MarkdownSpan>()
        scanInline(blockText, 0, blockText.length, docOffset, spans)
        return spans
    }

    // ──────────────────────────────────────────────────────────────────────
    // 블록 타입별 처리
    // ──────────────────────────────────────────────────────────────────────

    private fun headingSpans(
        level: Int,
        blockText: String,
        docOffset: Int,
    ): List<MarkdownSpan> {
        val spans = mutableListOf<MarkdownSpan>()
        // "# " (level 개의 # + 공백)
        val markerLen = (level + 1).coerceAtMost(blockText.length)
        spans += MarkdownSpan(docOffset until docOffset + markerLen, MarkdownInlineRole.Marker)
        val contentEnd = docOffset + blockText.length
        if (docOffset + markerLen < contentEnd) {
            spans += MarkdownSpan(docOffset + markerLen until contentEnd, MarkdownInlineRole.Heading(level))
            // heading 컨텐츠 내부 인라인 스타일
            scanInline(blockText, markerLen, blockText.length, docOffset, spans)
        }
        return spans
    }

    /** TextBlock, Blockquote, BulletList, OrderedList 등 줄 단위 스캔 */
    private fun lineScannedSpans(
        blockText: String,
        docOffset: Int,
    ): List<MarkdownSpan> {
        val spans = mutableListOf<MarkdownSpan>()
        val lines = blockText.split('\n')
        var lineStart = 0
        for (line in lines) {
            val lineDocStart = docOffset + lineStart
            val contentStart = hideLinePrefix(line, lineDocStart, spans)
            scanInline(line, contentStart, line.length, lineDocStart, spans)
            lineStart += line.length + 1
        }
        return spans
    }

    /**
     * 줄 앞의 블록 레벨 마커(>, -, *, +, 숫자.)를 숨기고 컨텐츠 시작 인덱스를 반환.
     * 마커가 없으면 0 반환.
     */
    private fun hideLinePrefix(
        line: String,
        lineDocStart: Int,
        spans: MutableList<MarkdownSpan>,
    ): Int {
        // Blockquote / Callout: ">" 를 투명 처리 (정상 크기 유지 → 테두리와 자연스러운 간격)
        if (line.startsWith(">")) {
            var pos = 0
            while (pos < line.length && line[pos] == '>') {
                spans += MarkdownSpan(lineDocStart + pos until lineDocStart + pos + 1, MarkdownInlineRole.BlockTransparent)
                pos++
                if (pos < line.length && line[pos] == ' ') pos++ // 공백은 유지
            }
            return pos
        }
        // 들여쓰기 계산 (중첩 리스트)
        var indent = 0
        while (indent < line.length && (line[indent] == ' ' || line[indent] == '\t')) indent++

        val rest = line.substring(indent)

        markdownTaskPrefixRegex.find(rest)?.takeIf { it.range.first == 0 }?.let { match ->
            val markerEnd = indent + match.value.length
            val checked = match.groupValues[3].equals("x", ignoreCase = true)
            spans += MarkdownSpan(
                lineDocStart until lineDocStart + markerEnd,
                MarkdownInlineRole.TaskPrefix(checked),
            )
            if (checked && markerEnd < line.length) {
                spans += MarkdownSpan(
                    lineDocStart + markerEnd until lineDocStart + line.length,
                    MarkdownInlineRole.CheckedTaskBody,
                )
            }
            return markerEnd
        }

        // CommonMark unordered list: "- ", "* ", or "+ "
        if (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ")) {
            val markerEnd = indent + 2
            spans += MarkdownSpan(lineDocStart until lineDocStart + markerEnd, MarkdownInlineRole.BulletPrefix)
            return markerEnd
        }

        // Ordered list: "숫자. "
        val orderedMatch = Regex("^(\\d+)\\. ").find(rest)
        if (orderedMatch != null) {
            val markerEnd = indent + orderedMatch.value.length
            spans += MarkdownSpan(lineDocStart until lineDocStart + markerEnd, MarkdownInlineRole.OrderedPrefix)
            return markerEnd
        }

        return 0
    }

    // ──────────────────────────────────────────────────────────────────────
    // 인라인 스캔
    // ──────────────────────────────────────────────────────────────────────

    /**
     * [from, to) 범위의 인라인 마커를 스캔해 spans에 추가한다.
     * 모든 인덱스는 line 내 상대 인덱스이며, lineDocStart를 더해 절대 위치로 변환.
     */
    private fun scanInline(
        line: String,
        from: Int,
        to: Int,
        lineDocStart: Int,
        spans: MutableList<MarkdownSpan>,
    ) {
        var i = from
        while (i < to) {
            val ch = line[i]
            when {
                // CommonMark escape: preview에서는 escape marker만 숨기고 다음 문자는 literal로 둔다.
                i + 1 < to && ch == '\\' && line[i + 1] in escapableAsciiPunctuation -> {
                    spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 1), MarkdownInlineRole.Marker)
                    i += 2
                }

                // Bold+Italic: ***text***
                i + 2 < to && ch == '*' && line[i+1] == '*' && line[i+2] == '*' -> {
                    val close = line.indexOf("***", i + 3).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 3), MarkdownInlineRole.Marker)
                        if (i + 3 < close)
                            spans += MarkdownSpan(abs(lineDocStart + i + 3, lineDocStart + close), MarkdownInlineRole.BoldItalic)
                        spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 3), MarkdownInlineRole.Marker)
                        i = close + 3
                    } else i++
                }

                // CommonMark underscore emphasis. Intraword underscores (snake_case) stay literal.
                ch == '_' -> {
                    val runLength = when {
                        i + 2 < to && line[i + 1] == '_' && line[i + 2] == '_' -> 3
                        i + 1 < to && line[i + 1] == '_' -> 2
                        else -> 1
                    }
                    val marker = "_".repeat(runLength)
                    val canOpen = i + runLength < to && !line[i + runLength].isWhitespace() &&
                        !(i > from && line[i - 1].isLetterOrDigit() && line[i + runLength].isLetterOrDigit())
                    val close = if (canOpen) line.indexOf(marker, i + runLength).takeIf { candidate ->
                        candidate in 0 until to && candidate > i + runLength &&
                            !line[candidate - 1].isWhitespace() &&
                            !(candidate + runLength < to && line[candidate - 1].isLetterOrDigit() &&
                                line[candidate + runLength].isLetterOrDigit())
                    } else null
                    if (close != null) {
                        val role = when (runLength) {
                            3 -> MarkdownInlineRole.BoldItalic
                            2 -> MarkdownInlineRole.Bold
                            else -> MarkdownInlineRole.Italic
                        }
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + runLength), MarkdownInlineRole.Marker)
                        spans += MarkdownSpan(abs(lineDocStart + i + runLength, lineDocStart + close), role)
                        spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + runLength), MarkdownInlineRole.Marker)
                        i = close + runLength
                    } else {
                        i += runLength
                    }
                }

                // Bold: **text**
                i + 1 < to && ch == '*' && line[i+1] == '*' -> {
                    val close = line.indexOf("**", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 2), MarkdownInlineRole.Marker)
                        if (i + 2 < close)
                            spans += MarkdownSpan(abs(lineDocStart + i + 2, lineDocStart + close), MarkdownInlineRole.Bold)
                        spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 2), MarkdownInlineRole.Marker)
                        i = close + 2
                    } else i++
                }

                // Strikethrough: ~~text~~
                i + 1 < to && ch == '~' && line[i+1] == '~' -> {
                    val close = line.indexOf("~~", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 2), MarkdownInlineRole.Marker)
                        if (i + 2 < close)
                            spans += MarkdownSpan(abs(lineDocStart + i + 2, lineDocStart + close), MarkdownInlineRole.Strikethrough)
                        spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 2), MarkdownInlineRole.Marker)
                        i = close + 2
                    } else i++
                }

                // Highlight: ==text==
                i + 1 < to && ch == '=' && line[i+1] == '=' -> {
                    val close = line.indexOf("==", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 2), MarkdownInlineRole.Marker)
                        if (i + 2 < close)
                            spans += MarkdownSpan(abs(lineDocStart + i + 2, lineDocStart + close), MarkdownInlineRole.Highlight)
                        spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 2), MarkdownInlineRole.Marker)
                        i = close + 2
                    } else i++
                }

                // InlineCode: `text`
                // 단, 연속 3개 이상의 backtick 은 코드 블록 fence — inline code 매칭 대상 아님.
                // 또한 길이 0 inline code (인접 backtick) 도 매칭 거름 → BlockDecorationDrawer 의
                // 길이 0 RoundRect 배경 표시 원천 차단. (raw 블록의 ``` 가 일반 텍스트로 보이게)
                ch == '`' -> {
                    var runLen = 0
                    while (i + runLen < to && line[i + runLen] == '`') runLen++
                    if (runLen >= 3) {
                        i += runLen
                    } else {
                        val close = line.indexOf('`', i + 1).takeIf { it in 0 until to }
                        if (close != null && close > i + 1) {
                            spans += MarkdownSpan(
                                abs(lineDocStart + i, lineDocStart + i + 1),
                                MarkdownInlineRole.InlineCodeMarker,
                            )
                            spans += MarkdownSpan(abs(lineDocStart + i + 1, lineDocStart + close), MarkdownInlineRole.InlineCode)
                            spans += MarkdownSpan(
                                abs(lineDocStart + close, lineDocStart + close + 1),
                                MarkdownInlineRole.InlineCodeMarker,
                            )
                            i = close + 1
                        } else i++
                    }
                }

                // EmbedLink: ![[파일명]]
                i + 2 < to && ch == '!' && line[i+1] == '[' && line[i+2] == '[' -> {
                    val close = line.indexOf("]]", i + 3).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 3), MarkdownInlineRole.HiddenSyntax)
                        if (i + 3 < close)
                            spans += MarkdownSpan(abs(lineDocStart + i + 3, lineDocStart + close), MarkdownInlineRole.Embed)
                        spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 2), MarkdownInlineRole.HiddenSyntax) // ]]
                        i = close + 2
                    } else i++
                }

                // WikiLink: [[target]] or [[target|alias]]
                i + 1 < to && ch == '[' && line[i+1] == '[' -> {
                    val close = line.indexOf("]]", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        val inner = line.substring(i + 2, close)
                        val pipe  = inner.indexOf('|')
                        if (pipe == -1) {
                            // [[target]] → [[ 숨김, target 링크색, ]] 숨김
                            spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 2), MarkdownInlineRole.HiddenSyntax)
                            if (i + 2 < close)
                                spans += MarkdownSpan(abs(lineDocStart + i + 2, lineDocStart + close), MarkdownInlineRole.Link)
                            spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 2), MarkdownInlineRole.HiddenSyntax)
                        } else {
                            // [[target|alias]] → [[target| 숨김, alias 링크색, ]] 숨김
                            val aliasStart = i + 2 + pipe + 1
                            spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + aliasStart), MarkdownInlineRole.HiddenSyntax)
                            if (aliasStart < close)
                                spans += MarkdownSpan(abs(lineDocStart + aliasStart, lineDocStart + close), MarkdownInlineRole.Link)
                            spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 2), MarkdownInlineRole.HiddenSyntax)
                        }
                        i = close + 2
                    } else i++
                }

                // ExternalLink: [text](url)
                ch == '[' -> {
                    val closeBracket = line.indexOf(']', i + 1).takeIf { it in 0 until to }
                    val openParen    = closeBracket?.let { if (it + 1 < to && line[it + 1] == '(') it + 1 else null }
                    val closeParen   = openParen?.let { line.indexOf(')', it + 1).takeIf { p -> p in 0 until to } }
                    if (closeBracket != null && openParen != null && closeParen != null &&
                        line.indexOf('[', i + 1).let { it < 0 || it >= closeBracket }
                    ) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 1), MarkdownInlineRole.HiddenSyntax) // [
                        if (i + 1 < closeBracket)
                            spans += MarkdownSpan(abs(lineDocStart + i + 1, lineDocStart + closeBracket), MarkdownInlineRole.Link) // text
                        spans += MarkdownSpan(abs(lineDocStart + closeBracket, lineDocStart + closeParen + 1), MarkdownInlineRole.HiddenSyntax) // ](url)
                        i = closeParen + 1
                    } else i++
                }

                // Bare external URL
                line.startsWith("https://", i) || line.startsWith("http://", i) -> {
                    var end = i
                    while (end < to && !line[end].isWhitespace() && line[end] !in "<>()") end++
                    while (end > i && line[end - 1] in ".,;:!?") end--
                    if (end > i) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + end), MarkdownInlineRole.Link)
                        i = end
                    } else i++
                }

                // Italic: *text*  (** 는 위에서 처리됨)
                ch == '*' -> {
                    val close = line.indexOf('*', i + 1).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += MarkdownSpan(abs(lineDocStart + i, lineDocStart + i + 1), MarkdownInlineRole.Marker)
                        if (i + 1 < close)
                            spans += MarkdownSpan(abs(lineDocStart + i + 1, lineDocStart + close), MarkdownInlineRole.Italic)
                        spans += MarkdownSpan(abs(lineDocStart + close, lineDocStart + close + 1), MarkdownInlineRole.Marker)
                        i = close + 1
                    } else i++
                }

                else -> i++
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 유틸
    // ──────────────────────────────────────────────────────────────────────

    /** 절대 범위 생성 (start inclusive, end exclusive → IntRange inclusive) */
    private fun abs(start: Int, end: Int): IntRange = start until end

    private const val escapableAsciiPunctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}
