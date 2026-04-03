package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

import androidx.compose.ui.text.SpanStyle

/**
 * 비활성 블록의 raw 텍스트에 적용할 SpanStyle 범위 리스트를 계산한다.
 *
 * 전략: 서식 마커(**, ~~, # 등)를 fontSize=0.sp + Color.Transparent 로 설정해
 * 시각적으로 완전히 사라지게 하고, 컨텐츠에는 해당 스타일(bold, italic 등)을 적용한다.
 * 텍스트 길이는 변하지 않으므로 cursor 위치 매핑이 자연스럽게 유지된다.
 */
internal object InlineStyleScanner {

    /**
     * @param block      파서가 인식한 블록 타입 (처리 전략 결정에 사용)
     * @param blockText  블록의 raw 텍스트 (blockRanges 기준)
     * @param docOffset  블록의 문서 내 시작 오프셋
     * @param config     서식 스타일 설정
     * @return (문서 내 절대 범위, SpanStyle) 쌍의 리스트
     */
    fun computeSpans(
        block: MarkdownBlock,
        blockText: String,
        docOffset: Int,
        config: MarkdownStyleConfig,
    ): List<Pair<IntRange, SpanStyle>> {
        if (blockText.isEmpty()) return emptyList()
        return when (block) {
            is MarkdownBlock.CodeBlock    -> emptyList()     // raw 마크다운 그대로
            is MarkdownBlock.Table        -> emptyList()     // 표는 raw 그대로
            is MarkdownBlock.HorizontalRule -> listOf((docOffset until docOffset + blockText.length) to config.marker)
            is MarkdownBlock.Embed        -> embedSpans(blockText, docOffset, config)
            is MarkdownBlock.Heading      -> headingSpans(block.level, blockText, docOffset, config)
            else                          -> lineScannedSpans(blockText, docOffset, config)
        }
    }

    /**
     * 블록 prefix가 없는 여러 줄 텍스트에서 인라인 마커를 줄을 넘어 스캔한다.
     * 연속된 일반 텍스트 줄(헤딩·코드블록·블록prefix 없는)에만 사용.
     */
    fun computeMultiLineSpans(
        blockText: String,
        docOffset: Int,
        config: MarkdownStyleConfig,
    ): List<Pair<IntRange, SpanStyle>> {
        if (blockText.isEmpty()) return emptyList()
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        scanInline(blockText, 0, blockText.length, docOffset, spans, config)
        return spans
    }

    // ──────────────────────────────────────────────────────────────────────
    // 블록 타입별 처리
    // ──────────────────────────────────────────────────────────────────────

    private fun headingSpans(
        level: Int,
        blockText: String,
        docOffset: Int,
        config: MarkdownStyleConfig,
    ): List<Pair<IntRange, SpanStyle>> {
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        // "# " (level 개의 # + 공백)
        val markerLen = (level + 1).coerceAtMost(blockText.length)
        spans += (docOffset until docOffset + markerLen) to config.marker
        val contentEnd = docOffset + blockText.length
        if (docOffset + markerLen < contentEnd) {
            spans += (docOffset + markerLen until contentEnd) to config.headingStyle(level)
            // heading 컨텐츠 내부 인라인 스타일
            scanInline(blockText, markerLen, blockText.length, docOffset, spans, config)
        }
        return spans
    }

    private fun embedSpans(
        blockText: String,
        docOffset: Int,
        config: MarkdownStyleConfig,
    ): List<Pair<IntRange, SpanStyle>> {
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        // ![[파일명]] 혹은 ![[파일명#섹션]]
        val start = blockText.indexOf("![[")
        val end   = blockText.indexOf("]]")
        if (start >= 0 && end > start) {
            spans += (docOffset + start until docOffset + start + 3) to config.marker  // ![[
            spans += (docOffset + start + 3 until docOffset + end) to config.link       // 파일명
            spans += (docOffset + end until docOffset + end + 2) to config.marker       // ]]
        }
        return spans
    }

    /**
     * Callout 블록 전용 스팬 계산.
     *
     * 첫 줄: `> [!TYPE] Title` → `> [!TYPE] ` 숨김, Title에 bold 적용
     * 나머지: `> Content` → `> ` 숨김, Content에 인라인 스캔
     */
    fun calloutSpans(
        blockText: String,
        docOffset: Int,
        config: MarkdownStyleConfig,
    ): List<Pair<IntRange, SpanStyle>> {
        if (blockText.isEmpty()) return emptyList()
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        val lines = blockText.split('\n')
        var lineStart = 0

        for ((idx, line) in lines.withIndex()) {
            val lineDocStart = docOffset + lineStart
            if (idx == 0) {
                // 첫 줄: "> [!TYPE] Title" or ">[!TYPE]" or ">> [!TYPE]" (공백 선택적, 다중 depth)
                val headerRegex = Regex("^>+ ?\\[!\\w+]\\s*")
                val match = headerRegex.find(line)
                if (match != null) {
                    val markerEnd = match.range.last + 1
                    // "> [!TYPE] " → 전체 숨김
                    spans += (lineDocStart until lineDocStart + markerEnd) to config.marker
                    // Title 부분 → bold + 인라인 스캔
                    if (markerEnd < line.length) {
                        spans += (lineDocStart + markerEnd until lineDocStart + line.length) to config.bold
                        scanInline(line, markerEnd, line.length, lineDocStart, spans, config)
                    }
                } else {
                    // fallback: 일반 줄 처리
                    val contentStart = hideLinePrefix(line, lineDocStart, spans, config)
                    scanInline(line, contentStart, line.length, lineDocStart, spans, config)
                }
            } else {
                // 나머지 줄: "> Content"
                val contentStart = hideLinePrefix(line, lineDocStart, spans, config)
                scanInline(line, contentStart, line.length, lineDocStart, spans, config)
            }
            lineStart += line.length + 1
        }
        return spans
    }

    /** TextBlock, Blockquote, BulletList, OrderedList 등 줄 단위 스캔 */
    private fun lineScannedSpans(
        blockText: String,
        docOffset: Int,
        config: MarkdownStyleConfig,
    ): List<Pair<IntRange, SpanStyle>> {
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        val lines = blockText.split('\n')
        var lineStart = 0
        for (line in lines) {
            val lineDocStart = docOffset + lineStart
            val contentStart = hideLinePrefix(line, lineDocStart, spans, config)
            scanInline(line, contentStart, line.length, lineDocStart, spans, config)
            lineStart += line.length + 1
        }
        return spans
    }

    /**
     * 줄 앞의 블록 레벨 마커(>, -, *, 숫자.)를 숨기고 컨텐츠 시작 인덱스를 반환.
     * 마커가 없으면 0 반환.
     */
    private fun hideLinePrefix(
        line: String,
        lineDocStart: Int,
        spans: MutableList<Pair<IntRange, SpanStyle>>,
        config: MarkdownStyleConfig,
    ): Int {
        // Blockquote / Callout: ">" 를 투명 처리 (정상 크기 유지 → 테두리와 자연스러운 간격)
        if (line.startsWith(">")) {
            var pos = 0
            while (pos < line.length && line[pos] == '>') {
                spans += (lineDocStart + pos until lineDocStart + pos + 1) to config.blockTransparent
                pos++
                if (pos < line.length && line[pos] == ' ') pos++ // 공백은 유지
            }
            return pos
        }
        // 들여쓰기 계산 (중첩 리스트)
        var indent = 0
        while (indent < line.length && (line[indent] == ' ' || line[indent] == '\t')) indent++

        val rest = line.substring(indent)

        // Unordered list: "- " or "* "
        if (rest.startsWith("- ") || rest.startsWith("* ")) {
            val markerEnd = indent + 2
            spans += (lineDocStart until lineDocStart + markerEnd) to config.bulletPrefix
            return markerEnd
        }

        // Ordered list: "숫자. "
        val orderedMatch = Regex("^(\\d+)\\. ").find(rest)
        if (orderedMatch != null) {
            val markerEnd = indent + orderedMatch.value.length
            spans += (lineDocStart until lineDocStart + markerEnd) to config.orderedPrefix
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
        spans: MutableList<Pair<IntRange, SpanStyle>>,
        config: MarkdownStyleConfig,
    ) {
        var i = from
        while (i < to) {
            val ch = line[i]
            when {
                // Bold+Italic: ***text***
                i + 2 < to && ch == '*' && line[i+1] == '*' && line[i+2] == '*' -> {
                    val close = line.indexOf("***", i + 3).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 3) to config.marker
                        if (i + 3 < close)
                            spans += abs(lineDocStart + i + 3, lineDocStart + close) to config.boldItalic
                        spans += abs(lineDocStart + close, lineDocStart + close + 3) to config.marker
                        i = close + 3
                    } else i++
                }

                // Bold: **text**
                i + 1 < to && ch == '*' && line[i+1] == '*' -> {
                    val close = line.indexOf("**", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 2) to config.marker
                        if (i + 2 < close)
                            spans += abs(lineDocStart + i + 2, lineDocStart + close) to config.bold
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to config.marker
                        i = close + 2
                    } else i++
                }

                // Strikethrough: ~~text~~
                i + 1 < to && ch == '~' && line[i+1] == '~' -> {
                    val close = line.indexOf("~~", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 2) to config.marker
                        if (i + 2 < close)
                            spans += abs(lineDocStart + i + 2, lineDocStart + close) to config.strikethrough
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to config.marker
                        i = close + 2
                    } else i++
                }

                // Highlight: ==text==
                i + 1 < to && ch == '=' && line[i+1] == '=' -> {
                    val close = line.indexOf("==", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 2) to config.marker
                        if (i + 2 < close)
                            spans += abs(lineDocStart + i + 2, lineDocStart + close) to config.highlight
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to config.marker
                        i = close + 2
                    } else i++
                }

                // InlineCode: `text`
                ch == '`' -> {
                    val close = line.indexOf('`', i + 1).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 1) to config.marker
                        if (i + 1 < close)
                            spans += abs(lineDocStart + i + 1, lineDocStart + close) to config.codeInline
                        spans += abs(lineDocStart + close, lineDocStart + close + 1) to config.marker
                        i = close + 1
                    } else i++
                }

                // EmbedLink: ![[파일명]]
                i + 2 < to && ch == '!' && line[i+1] == '[' && line[i+2] == '[' -> {
                    val close = line.indexOf("]]", i + 3).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 3) to config.marker   // ![[
                        if (i + 3 < close)
                            spans += abs(lineDocStart + i + 3, lineDocStart + close) to config.link
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to config.marker // ]]
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
                            spans += abs(lineDocStart + i, lineDocStart + i + 2) to config.marker
                            if (i + 2 < close)
                                spans += abs(lineDocStart + i + 2, lineDocStart + close) to config.link
                            spans += abs(lineDocStart + close, lineDocStart + close + 2) to config.marker
                        } else {
                            // [[target|alias]] → [[target| 숨김, alias 링크색, ]] 숨김
                            val aliasStart = i + 2 + pipe + 1
                            spans += abs(lineDocStart + i, lineDocStart + aliasStart) to config.marker
                            if (aliasStart < close)
                                spans += abs(lineDocStart + aliasStart, lineDocStart + close) to config.link
                            spans += abs(lineDocStart + close, lineDocStart + close + 2) to config.marker
                        }
                        i = close + 2
                    } else i++
                }

                // ExternalLink: [text](url)
                ch == '[' -> {
                    val closeBracket = line.indexOf(']', i + 1).takeIf { it in 0 until to }
                    val openParen    = closeBracket?.let { if (it + 1 < to && line[it + 1] == '(') it + 1 else null }
                    val closeParen   = openParen?.let { line.indexOf(')', it + 1).takeIf { p -> p in 0 until to } }
                    if (closeBracket != null && openParen != null && closeParen != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 1) to config.marker          // [
                        if (i + 1 < closeBracket)
                            spans += abs(lineDocStart + i + 1, lineDocStart + closeBracket) to config.link  // text
                        spans += abs(lineDocStart + closeBracket, lineDocStart + closeParen + 1) to config.marker // ](url)
                        i = closeParen + 1
                    } else i++
                }

                // Italic: *text*  (** 는 위에서 처리됨)
                ch == '*' -> {
                    val close = line.indexOf('*', i + 1).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 1) to config.marker
                        if (i + 1 < close)
                            spans += abs(lineDocStart + i + 1, lineDocStart + close) to config.italic
                        spans += abs(lineDocStart + close, lineDocStart + close + 1) to config.marker
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
}
