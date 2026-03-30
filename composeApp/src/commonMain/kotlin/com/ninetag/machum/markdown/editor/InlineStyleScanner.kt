package com.ninetag.machum.markdown.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.ninetag.machum.markdown.token.MarkdownBlock

/**
 * 비활성 블록의 raw 텍스트에 적용할 SpanStyle 범위 리스트를 계산한다.
 *
 * 전략: 서식 마커(**, ~~, # 등)를 fontSize=0.sp + Color.Transparent 로 설정해
 * 시각적으로 완전히 사라지게 하고, 컨텐츠에는 해당 스타일(bold, italic 등)을 적용한다.
 * 텍스트 길이는 변하지 않으므로 cursor 위치 매핑이 자연스럽게 유지된다.
 */
internal object InlineStyleScanner {

    /** 마커를 zero-size + 투명으로 숨김 */
    private val MARKER = SpanStyle(fontSize = 0.01.sp, color = Color.Transparent)

    private val BOLD        = SpanStyle(fontWeight = FontWeight.Bold)
    private val ITALIC      = SpanStyle(fontStyle = FontStyle.Italic)
    private val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    private val STRIKE      = SpanStyle(textDecoration = TextDecoration.LineThrough)
    private val HIGHLIGHT   = SpanStyle(background = Color(0xFFFFEB3B))
    private val CODE_INLINE = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))
    private val CODE_BLOCK  = SpanStyle(fontFamily = FontFamily.Monospace)
    private val LINK        = SpanStyle(color = Color(0xFF1565C0))

    /**
     * @param block      파서가 인식한 블록 타입 (처리 전략 결정에 사용)
     * @param blockText  블록의 raw 텍스트 (blockRanges 기준)
     * @param docOffset  블록의 문서 내 시작 오프셋
     * @return (문서 내 절대 범위, SpanStyle) 쌍의 리스트
     */
    fun computeSpans(
        block: MarkdownBlock,
        blockText: String,
        docOffset: Int,
    ): List<Pair<IntRange, SpanStyle>> {
        if (blockText.isEmpty()) return emptyList()
        return when (block) {
            is MarkdownBlock.CodeBlock    -> codeBlockSpans(blockText, docOffset)
            is MarkdownBlock.Table        -> emptyList()     // 표는 raw 그대로
            is MarkdownBlock.HorizontalRule -> emptyList()
            is MarkdownBlock.Embed        -> embedSpans(blockText, docOffset)
            is MarkdownBlock.Heading      -> headingSpans(block.level, blockText, docOffset)
            else                          -> lineScannedSpans(blockText, docOffset)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 블록 타입별 처리
    // ──────────────────────────────────────────────────────────────────────

    private fun headingSpans(
        level: Int,
        blockText: String,
        docOffset: Int,
    ): List<Pair<IntRange, SpanStyle>> {
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        // "# " (level 개의 # + 공백)
        val markerLen = (level + 1).coerceAtMost(blockText.length)
        spans += (docOffset until docOffset + markerLen) to MARKER
        val contentEnd = docOffset + blockText.length
        if (docOffset + markerLen < contentEnd) {
            spans += (docOffset + markerLen until contentEnd) to headingStyle(level)
            // heading 컨텐츠 내부 인라인 스타일
            scanInline(blockText, markerLen, blockText.length, docOffset, spans)
        }
        return spans
    }

    private fun codeBlockSpans(
        blockText: String,
        docOffset: Int,
    ): List<Pair<IntRange, SpanStyle>> {
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        val lines = blockText.split('\n')
        var lineStart = 0
        lines.forEachIndexed { idx, line ->
            val lineDocStart = docOffset + lineStart
            val isFence = line.trimStart().startsWith("```")
            if (isFence) {
                // ``` 펜스 라인 숨김
                spans += (lineDocStart until lineDocStart + line.length) to MARKER
            } else {
                // 코드 본문: 모노스페이스만 (인라인 스캔 없음)
                spans += (lineDocStart until lineDocStart + line.length) to CODE_BLOCK
            }
            lineStart += line.length + 1
        }
        return spans
    }

    private fun embedSpans(
        blockText: String,
        docOffset: Int,
    ): List<Pair<IntRange, SpanStyle>> {
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        // ![[파일명]] 혹은 ![[파일명#섹션]]
        val start = blockText.indexOf("![[")
        val end   = blockText.indexOf("]]")
        if (start >= 0 && end > start) {
            spans += (docOffset + start until docOffset + start + 3) to MARKER  // ![[
            spans += (docOffset + start + 3 until docOffset + end) to LINK       // 파일명
            spans += (docOffset + end until docOffset + end + 2) to MARKER       // ]]
        }
        return spans
    }

    /** TextBlock, Blockquote, BulletList, OrderedList, Callout 등 줄 단위 스캔 */
    private fun lineScannedSpans(
        blockText: String,
        docOffset: Int,
    ): List<Pair<IntRange, SpanStyle>> {
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
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
     * 줄 앞의 블록 레벨 마커(>, -, *, 숫자.)를 숨기고 컨텐츠 시작 인덱스를 반환.
     * 마커가 없으면 0 반환.
     */
    private fun hideLinePrefix(
        line: String,
        lineDocStart: Int,
        spans: MutableList<Pair<IntRange, SpanStyle>>,
    ): Int {
        // Blockquote / Callout: "> "
        if (line.startsWith("> ")) {
            spans += (lineDocStart until lineDocStart + 2) to MARKER
            return 2
        }
        if (line == ">") {
            spans += (lineDocStart until lineDocStart + 1) to MARKER
            return 1
        }
        // 들여쓰기 계산 (중첩 리스트)
        var indent = 0
        while (indent < line.length && (line[indent] == ' ' || line[indent] == '\t')) indent++

        val rest = line.substring(indent)

        // Unordered list: "- " or "* "
        if (rest.startsWith("- ") || rest.startsWith("* ")) {
            val markerEnd = indent + 2
            spans += (lineDocStart until lineDocStart + markerEnd) to MARKER
            return markerEnd
        }

        // Ordered list: "숫자. "
        val orderedMatch = Regex("^(\\d+)\\. ").find(rest)
        if (orderedMatch != null) {
            val markerEnd = indent + orderedMatch.value.length
            spans += (lineDocStart until lineDocStart + markerEnd) to MARKER
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
    ) {
        var i = from
        while (i < to) {
            val ch = line[i]
            when {
                // Bold+Italic: ***text***
                i + 2 < to && ch == '*' && line[i+1] == '*' && line[i+2] == '*' -> {
                    val close = line.indexOf("***", i + 3).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 3) to MARKER
                        if (i + 3 < close)
                            spans += abs(lineDocStart + i + 3, lineDocStart + close) to BOLD_ITALIC
                        spans += abs(lineDocStart + close, lineDocStart + close + 3) to MARKER
                        i = close + 3
                    } else i++
                }

                // Bold: **text**
                i + 1 < to && ch == '*' && line[i+1] == '*' -> {
                    val close = line.indexOf("**", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 2) to MARKER
                        if (i + 2 < close)
                            spans += abs(lineDocStart + i + 2, lineDocStart + close) to BOLD
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to MARKER
                        i = close + 2
                    } else i++
                }

                // Strikethrough: ~~text~~
                i + 1 < to && ch == '~' && line[i+1] == '~' -> {
                    val close = line.indexOf("~~", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 2) to MARKER
                        if (i + 2 < close)
                            spans += abs(lineDocStart + i + 2, lineDocStart + close) to STRIKE
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to MARKER
                        i = close + 2
                    } else i++
                }

                // Highlight: ==text==
                i + 1 < to && ch == '=' && line[i+1] == '=' -> {
                    val close = line.indexOf("==", i + 2).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 2) to MARKER
                        if (i + 2 < close)
                            spans += abs(lineDocStart + i + 2, lineDocStart + close) to HIGHLIGHT
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to MARKER
                        i = close + 2
                    } else i++
                }

                // InlineCode: `text`
                ch == '`' -> {
                    val close = line.indexOf('`', i + 1).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 1) to MARKER
                        if (i + 1 < close)
                            spans += abs(lineDocStart + i + 1, lineDocStart + close) to CODE_INLINE
                        spans += abs(lineDocStart + close, lineDocStart + close + 1) to MARKER
                        i = close + 1
                    } else i++
                }

                // EmbedLink: ![[파일명]]
                i + 2 < to && ch == '!' && line[i+1] == '[' && line[i+2] == '[' -> {
                    val close = line.indexOf("]]", i + 3).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 3) to MARKER   // ![[
                        if (i + 3 < close)
                            spans += abs(lineDocStart + i + 3, lineDocStart + close) to LINK
                        spans += abs(lineDocStart + close, lineDocStart + close + 2) to MARKER // ]]
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
                            spans += abs(lineDocStart + i, lineDocStart + i + 2) to MARKER
                            if (i + 2 < close)
                                spans += abs(lineDocStart + i + 2, lineDocStart + close) to LINK
                            spans += abs(lineDocStart + close, lineDocStart + close + 2) to MARKER
                        } else {
                            // [[target|alias]] → [[target| 숨김, alias 링크색, ]] 숨김
                            val aliasStart = i + 2 + pipe + 1
                            spans += abs(lineDocStart + i, lineDocStart + aliasStart) to MARKER
                            if (aliasStart < close)
                                spans += abs(lineDocStart + aliasStart, lineDocStart + close) to LINK
                            spans += abs(lineDocStart + close, lineDocStart + close + 2) to MARKER
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
                        spans += abs(lineDocStart + i, lineDocStart + i + 1) to MARKER          // [
                        if (i + 1 < closeBracket)
                            spans += abs(lineDocStart + i + 1, lineDocStart + closeBracket) to LINK  // text
                        spans += abs(lineDocStart + closeBracket, lineDocStart + closeParen + 1) to MARKER // ](url)
                        i = closeParen + 1
                    } else i++
                }

                // Italic: *text*  (** 는 위에서 처리됨)
                ch == '*' -> {
                    val close = line.indexOf('*', i + 1).takeIf { it in 0 until to }
                    if (close != null) {
                        spans += abs(lineDocStart + i, lineDocStart + i + 1) to MARKER
                        if (i + 1 < close)
                            spans += abs(lineDocStart + i + 1, lineDocStart + close) to ITALIC
                        spans += abs(lineDocStart + close, lineDocStart + close + 1) to MARKER
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

    private fun headingStyle(level: Int): SpanStyle = when (level) {
        1    -> SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
        2    -> SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
        3    -> SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
        4    -> SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
        5    -> SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
        else -> SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
