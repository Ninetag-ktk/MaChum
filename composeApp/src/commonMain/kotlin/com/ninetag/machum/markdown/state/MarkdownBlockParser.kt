package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import com.ninetag.machum.markdown.state.EditorBlock

/**
 * Raw markdown 문자열을 [EditorBlock] 리스트로 파싱한다.
 *
 * 블록 감지 패턴은 v1 `MarkdownPatternScanner`에서 가져옴.
 * Callout body는 재귀적으로 파싱하여 중첩 블록을 지원한다.
 */
object MarkdownBlockParser {

    private val calloutHeaderRegex = Regex("^(>+) ?\\[!(\\w+)]\\s*(.*)")

    fun parse(markdown: String): List<EditorBlock> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.split('\n')
        return parseLines(lines)
    }

    private fun parseLines(lines: List<String>): List<EditorBlock> {
        val blocks = mutableListOf<EditorBlock>()
        var i = 0
        val textAccum = StringBuilder()

        fun flushText() {
            if (textAccum.isNotEmpty()) {
                blocks += EditorBlock.Text(textFieldState = TextFieldState(textAccum.toString()))
                textAccum.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]

            when {
                // ── CodeBlock: ``` 펜스 ──
                line.trimStart().startsWith("```") -> {
                    flushText()
                    val lang = line.trim().removePrefix("```").trim()
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        codeLines.add(lines[i])
                        i++
                    }
                    if (i < lines.size) i++ // 닫는 펜스 건너뜀
                    blocks += EditorBlock.Code(
                        language = lang,
                        codeState = TextFieldState(codeLines.joinToString("\n")),
                    )
                }

                // ── Callout: > [!TYPE] ──
                calloutHeaderRegex.containsMatchIn(line) -> {
                    flushText()
                    val match = calloutHeaderRegex.find(line)!!
                    val calloutDepth = match.groupValues[1].length
                    val calloutType = match.groupValues[2]
                    val title = match.groupValues[3]

                    // 후속 ">" 줄 수집 (같은/상위 depth Callout 헤더에서 중단)
                    val calloutBodyLines = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val nextLine = lines[i]
                        if (!nextLine.startsWith(">")) break
                        val nextMatch = calloutHeaderRegex.find(nextLine)
                        if (nextMatch != null) {
                            val nextDepth = nextMatch.groupValues[1].length
                            if (nextDepth <= calloutDepth) break
                        }
                        calloutBodyLines.add(nextLine)
                        i++
                    }

                    // body 줄에서 ">" prefix 제거 후 재귀 파싱
                    val strippedBody = calloutBodyLines.map { bodyLine ->
                        when {
                            bodyLine.startsWith("> ") -> bodyLine.removePrefix("> ")
                            bodyLine.startsWith(">") -> bodyLine.removePrefix(">")
                            else -> bodyLine
                        }
                    }
                    val bodyBlocks = if (strippedBody.isNotEmpty()) {
                        parseLines(strippedBody)
                    } else {
                        emptyList()
                    }

                    blocks += EditorBlock.Callout(
                        calloutType = calloutType,
                        titleState = TextFieldState(title),
                        bodyBlocks = bodyBlocks,
                    )
                }

                // ── Table: | 시작 ──
                isTableLine(line) -> {
                    flushText()
                    val tableLines = mutableListOf(line)
                    i++
                    while (i < lines.size && isTableLine(lines[i])) {
                        tableLines.add(lines[i])
                        i++
                    }
                    if (tableLines.size >= 2) {
                        blocks += parseTable(tableLines)
                    } else {
                        // 1줄만 있으면 텍스트로 취급
                        if (textAccum.isNotEmpty()) textAccum.append('\n')
                        textAccum.append(tableLines.first())
                    }
                }

                // ── HorizontalRule: --- / *** / ___ ──
                isHorizontalRule(line) -> {
                    flushText()
                    blocks += EditorBlock.HorizontalRule()
                    i++
                }

                // ── Embed: ![[...]] ──
                isEmbedLine(line) -> {
                    flushText()
                    val trimmed = line.trim()
                    val target = trimmed.removePrefix("![[").removeSuffix("]]")
                    blocks += EditorBlock.Embed(target = target)
                    i++
                }

                // ── 빈 줄: TextBlock 분리 ──
                line.isEmpty() -> {
                    flushText()
                    i++
                }

                // ── 나머지: TextBlock에 축적 ──
                else -> {
                    if (textAccum.isNotEmpty()) textAccum.append('\n')
                    textAccum.append(line)
                    i++
                }
            }
        }

        flushText()
        return blocks
    }

    // ── 테이블 파싱 ──

    private fun parseTable(lines: List<String>): EditorBlock.Table {
        fun parseCells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|")
                .split("|")
                .map { it.trim() }

        val headers = parseCells(lines.first())
        // 구분자 줄(|---|---| 등) 건너뛰기
        val dataStartIndex = if (lines.size > 1 && lines[1].contains("---")) 2 else 1
        val rows = lines.drop(dataStartIndex).map { parseCells(it) }

        return EditorBlock.Table(
            headerStates = headers.map { TextFieldState(it) },
            rowStates = rows.map { row -> row.map { TextFieldState(it) } },
        )
    }

    // ── 줄 판별 헬퍼 (v1 MarkdownPatternScanner에서 가져옴) ──

    private fun isHorizontalRule(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val ch = trimmed[0]
        if (ch != '-' && ch != '*' && ch != '_') return false
        return trimmed.all { it == ch || it == ' ' }
    }

    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
    }

    private fun isEmbedLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("![[") && trimmed.endsWith("]]")
    }
}
