package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState

/**
 * Raw markdown 문자열을 [EditorBlock] 리스트로 파싱한다.
 *
 * 블록 감지 패턴은 v1 `MarkdownPatternScanner`에서 가져옴.
 * Callout body는 재귀적으로 파싱하여 중첩 블록을 지원한다.
 */
object MarkdownBlockParser {

    private val calloutHeaderRegex = Regex("^(>+) ?\\[!([\\w-]+)]([+-])?(\\s*)(.*)")
    private val codeFenceRegex = Regex("^( {0,3})(`{3,}|~{3,})(.*)$")
    private val tableDelimiterRegex = Regex("^:?-{3,}:?$")

    fun parse(markdown: String, excludeCalloutTypes: Set<String> = emptySet()): List<EditorBlock> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.split('\n')
        return parseLines(lines, excludeCalloutTypes)
    }

    private fun parseLines(lines: List<String>, excludeCalloutTypes: Set<String> = emptySet()): List<EditorBlock> {
        val blocks = mutableListOf<EditorBlock>()
        var i = 0
        val textAccum = StringBuilder()
        var pendingNewlines = 0  // 빈 줄 카운터

        fun flushText() {
            // 보류 중인 빈 줄을 textAccum에 반영
            if (pendingNewlines > 0) {
                if (textAccum.isNotEmpty()) {
                    // text + 빈 줄: trailing \n 추가
                    repeat(pendingNewlines) { textAccum.append('\n') }
                } else {
                    // Block→Block 사이 빈 줄: ZWSP(Zero-Width Space)로 구성된 TextBlock 생성
                    // "\n"은 TextField에서 2줄 높이지만, ZWSP은 1줄 높이 (정확한 빈 줄 렌더링)
                    // toMarkdown() 시 ZWSP → "" 치환으로 원본 빈 줄 복원
                    val marker = EditorBlock.BLANK_LINE_MARKER
                    val blankLines = (1..pendingNewlines).joinToString("\n") { marker }
                    textAccum.append(blankLines)
                }
            }
            pendingNewlines = 0
            if (textAccum.isNotEmpty()) {
                blocks += EditorBlock.Text(textFieldState = TextFieldState(textAccum.toString()))
                textAccum.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]

            when {
                // ── CodeBlock: backtick/tilde fence (닫는 펜스가 있을 때만 변환) ──
                codeFenceRegex.matches(line) -> {
                    val opening = codeFenceRegex.matchEntire(line)!!
                    val openingFence = opening.groupValues[2]
                    val openingSuffix = opening.groupValues[3]
                    val fenceChar = openingFence.first()
                    val validOpening = fenceChar != '`' || '`' !in openingSuffix
                    var closingIdx = i + 1
                    while (closingIdx < lines.size && !isClosingFence(lines[closingIdx], fenceChar, openingFence.length)) {
                        closingIdx++
                    }
                    if (validOpening && closingIdx < lines.size) {
                        val closing = codeFenceRegex.matchEntire(lines[closingIdx])!!
                        flushText()
                        val codeLines = lines.subList(i + 1, closingIdx)
                        val code = codeLines.joinToString("\n")
                        blocks += EditorBlock.Code(
                            language = openingSuffix.trim(),
                            codeState = TextFieldState(code),
                            openingIndent = opening.groupValues[1],
                            fence = openingFence,
                            openingSuffix = openingSuffix,
                            closingIndent = closing.groupValues[1],
                            closingFence = closing.groupValues[2],
                            closingSuffix = closing.groupValues[3],
                            emptyContentLineCount = if (code.isEmpty()) codeLines.size else 0,
                        )
                        i = closingIdx + 1
                    } else {
                        // 닫히지 않았거나 안전하게 구조화할 수 없는 fence run 전체를 raw 로 유지한다.
                        val rawEnd = if (closingIdx < lines.size) closingIdx else lines.lastIndex
                        if (pendingNewlines > 0) {
                            if (textAccum.isNotEmpty()) textAccum.append('\n')
                            repeat(pendingNewlines) { textAccum.append('\n') }
                            pendingNewlines = 0
                        } else if (textAccum.isNotEmpty()) {
                            textAccum.append('\n')
                        }
                        for (rawIndex in i..rawEnd) {
                            if (rawIndex > i) textAccum.append('\n')
                            textAccum.append(lines[rawIndex])
                        }
                        i = rawEnd + 1
                    }
                }

                // ── Callout: > [!TYPE] ──
                calloutHeaderRegex.containsMatchIn(line) -> {
                    val match = calloutHeaderRegex.find(line)!!
                    val calloutType = match.groupValues[2]

                    // excludeCalloutTypes に該当するタイプはテキストとして処理
                    if (excludeCalloutTypes.any { it.equals(calloutType, ignoreCase = true) }) {
                        // 제외 대상 → 일반 텍스트 줄로 처리
                        if (pendingNewlines > 0) {
                            if (textAccum.isNotEmpty()) textAccum.append('\n')
                            repeat(pendingNewlines) { textAccum.append('\n') }
                            pendingNewlines = 0
                        } else if (textAccum.isNotEmpty()) {
                            textAccum.append('\n')
                        }
                        textAccum.append(line)
                        i++
                    } else {
                        flushText()
                        val calloutDepth = match.groupValues[1].length
                        val foldMarker = match.groupValues[3].singleOrNull()
                        val titlePrefix = match.groupValues[4]
                        val title = match.groupValues[5]

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
                        val bodyLinePrefixes = calloutBodyLines.map { bodyLine ->
                            when {
                                bodyLine.startsWith("> ") -> "> "
                                bodyLine.startsWith(">") -> ">"
                                else -> ""
                            }
                        }
                        val strippedBody = calloutBodyLines.mapIndexed { index, bodyLine ->
                            bodyLine.removePrefix(bodyLinePrefixes[index])
                        }
                        // DL body 내부에서는 DL 중첩 금지
                        val bodyExcludes = if (calloutType.equals("DL", ignoreCase = true)) {
                            excludeCalloutTypes + "DL"
                        } else {
                            excludeCalloutTypes
                        }
                        val bodyBlocks = if (strippedBody.isNotEmpty()) {
                            parseLines(strippedBody, bodyExcludes)
                        } else {
                            emptyList()
                        }

                        blocks += EditorBlock.Callout(
                            calloutType = calloutType,
                            titleState = TextFieldState(title),
                            bodyBlocks = bodyBlocks,
                            foldMarker = foldMarker,
                            titlePrefix = titlePrefix,
                            bodyLinePrefixes = bodyLinePrefixes,
                        )
                    }
                }

                // ── Table: | 시작 + 두 번째 줄이 |---| 구분자 (둘 다 만족할 때만 변환) ──
                // 구분자 행 강제: dissolve 된 raw Table 에서 사용자가 |---| 행을 지웠을 때
                // 다시 Table 로 자동 변환되며 toMarkdown() 이 |---| 를 부활시키는 회귀를 막음.
                isTableLine(line) && !isEmbedLine(line) -> {
                    // 바로 다음 줄이 구분자인 경우에만 table run 의 끝을 찾는다.
                    // 구분자 없는 `| ... |` 연속 줄에서 매 줄마다 나머지 전체를 다시
                    // 훑는 O(n²) 경로를 피하며, 기존의 "두 번째 줄은 구분자" 계약은 그대로 유지한다.
                    val hasSeparator = i + 1 < lines.size &&
                        isTableDelimiterLine(lines[i + 1])

                    if (hasSeparator) {
                        // 유효한 테이블 → flushText 후 Table 블록 생성
                        var j = i + 2
                        while (j < lines.size && isTableLine(lines[j]) && !isEmbedLine(lines[j])) j++
                        flushText()
                        val tableLines = (i until j).map { lines[it] }
                        blocks += parseTable(tableLines)
                        i = j
                    } else {
                        // 1줄만 또는 구분자 행 없음 → TextBlock 에 유지 (flushText 하지 않음)
                        if (pendingNewlines > 0) {
                            if (textAccum.isNotEmpty()) textAccum.append('\n')
                            repeat(pendingNewlines) { textAccum.append('\n') }
                            pendingNewlines = 0
                        } else if (textAccum.isNotEmpty()) {
                            textAccum.append('\n')
                        }
                        textAccum.append(line)
                        i++
                    }
                }

                // ── HorizontalRule(---/***/___)는 TextBlock에 포함 ──
                // → 인라인 렌더링: 포커스 시 raw "---", 비활성 시 Divider
                // → MarkdownPatternScanner가 감지, BlockDecorationDrawer가 Divider 그림

                // ── Embed: ![[...]] ──
                isEmbedLine(line) -> {
                    flushText()
                    val trimmed = line.trim()
                    blocks += EditorBlock.Embed(
                        target = trimmed.removePrefix("![[").removeSuffix("]]"),
                    )
                    i++
                }

                // ── 빈 줄: 카운터에 누적 ──
                // 다음 텍스트가 올 때 정확한 \n 개수를 삽입
                line.isEmpty() -> {
                    pendingNewlines++
                    i++
                }

                // ── 나머지: TextBlock에 축적 ──
                else -> {
                    // 보류 중인 빈 줄 반영
                    if (pendingNewlines > 0) {
                        if (textAccum.isNotEmpty()) {
                            // text→blank→text: 줄 구분자 \n + 빈 줄 \n × N
                            textAccum.append('\n')
                        }
                        repeat(pendingNewlines) { textAccum.append('\n') }
                        pendingNewlines = 0
                    } else if (textAccum.isNotEmpty()) {
                        // 일반 줄 구분자
                        textAccum.append('\n')
                    }
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
        val headerLine = parseTableLine(lines.first())
        val headers = headerLine.cells
        val delimiterLine = parseTableLine(lines[1])
        // 구분자 줄(|---|---| 등) 건너뛰기
        val dataStartIndex = if (lines.size > 1 && isTableDelimiterLine(lines[1])) 2 else 1
        val parsedRows = lines.drop(dataStartIndex).map(::parseTableLine)
        val rows = parsedRows.map(ParsedTableLine::cells)
        val separatorColumnCount = lines.getOrNull(1)
            ?.takeIf(::isTableDelimiterLine)
            ?.let(::parseTableLine)
            ?.cells
            ?.size
            ?: 0
        val columnCount = maxOf(
            headers.size,
            separatorColumnCount,
            rows.maxOfOrNull { it.size } ?: 0,
        ).coerceAtLeast(1)

        fun normalize(cells: List<String>): List<String> =
            cells + List(columnCount - cells.size) { "" }

        return EditorBlock.Table(
            headerStates = normalize(headers).map { TextFieldState(it) },
            rowStates = rows.map { row -> normalize(row).map { TextFieldState(it) } },
            // Missing data cells are blank; every delimiter cell must remain valid Markdown.
            delimiterCells = delimiterLine.cells + List(columnCount - delimiterLine.cells.size) { "---" },
            leadingPipe = headerLine.leadingPipe,
            trailingPipe = headerLine.trailingPipe,
            delimiterLeadingPipe = delimiterLine.leadingPipe,
            delimiterTrailingPipe = delimiterLine.trailingPipe,
            rowLeadingPipes = parsedRows.map(ParsedTableLine::leadingPipe),
            rowTrailingPipes = parsedRows.map(ParsedTableLine::trailingPipe),
            headerSource = TableLineSource(lines.first(), headers),
            delimiterSource = TableLineSource(lines[1], delimiterLine.cells),
            rowSources = lines.drop(dataStartIndex).zip(parsedRows) { raw, parsed ->
                TableLineSource(raw, parsed.cells)
            },
        )
    }

    // ── 줄 판별 헬퍼 (v1 MarkdownPatternScanner에서 가져옴) ──
    // isHorizontalRule 제거 — HR은 TextBlock 인라인 렌더링으로 전환

    private fun isTableLine(line: String): Boolean {
        return parseTableLine(line).let { parsed ->
            parsed.cells.size >= 2 || parsed.leadingPipe && parsed.trailingPipe
        }
    }

    private fun isTableDelimiterLine(line: String): Boolean =
        parseTableLine(line).let { parsed ->
            (parsed.cells.size >= 2 || parsed.leadingPipe && parsed.trailingPipe) &&
                parsed.cells.all { tableDelimiterRegex.matches(it.trim()) }
        }

    private data class ParsedTableLine(
        val cells: List<String>,
        val leadingPipe: Boolean,
        val trailingPipe: Boolean,
    )

    private fun parseTableLine(line: String): ParsedTableLine {
        val trimmed = line.trim()
        val leadingPipe = trimmed.startsWith('|')
        val trailingPipe = trimmed.endsWith('|') && !isEscaped(trimmed, trimmed.lastIndex)
        val start = if (leadingPipe) 1 else 0
        val end = if (trailingPipe) trimmed.lastIndex else trimmed.length
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        for (index in start until end) {
            val char = trimmed[index]
            if (char == '|' && !isEscaped(trimmed, index)) {
                cells += cell.toString().trim()
                cell.clear()
            } else {
                cell.append(char)
            }
        }
        cells += cell.toString().trim()
        return ParsedTableLine(cells, leadingPipe, trailingPipe)
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var backslashes = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 == 1
    }

    private fun isClosingFence(line: String, fenceChar: Char, minimumLength: Int): Boolean {
        val match = codeFenceRegex.matchEntire(line) ?: return false
        val fence = match.groupValues[2]
        return fence.first() == fenceChar &&
            fence.length >= minimumLength &&
            match.groupValues[3].isBlank()
    }

    private fun isEmbedLine(line: String): Boolean {
        return standaloneMarkdownEmbedTarget(line) != null
    }
}
