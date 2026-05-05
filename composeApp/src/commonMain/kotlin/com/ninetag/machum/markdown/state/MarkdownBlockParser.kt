package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState

/**
 * Raw markdown 문자열을 [EditorBlock] 리스트로 파싱한다.
 *
 * 블록 감지 패턴은 v1 `MarkdownPatternScanner`에서 가져옴.
 * Callout body는 재귀적으로 파싱하여 중첩 블록을 지원한다.
 */
object MarkdownBlockParser {

    private val calloutHeaderRegex = Regex("^(>+) ?\\[!(\\w+)]\\s*(.*)")

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
                // ── CodeBlock: ``` 펜스 (닫는 펜스가 있을 때만 변환) ──
                line.trimStart().startsWith("```") -> {
                    // 닫는 ``` 존재 여부를 먼저 확인
                    var closingIdx = i + 1
                    while (closingIdx < lines.size && !lines[closingIdx].trimStart().startsWith("```")) {
                        closingIdx++
                    }
                    if (closingIdx < lines.size) {
                        // 닫는 펜스 있음 → CodeBlock 생성
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
                    } else {
                        // 닫는 펜스 없음 → TextBlock에 유지
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
                        )
                    }
                }

                // ── Table: | 시작 + 두 번째 줄이 |---| 구분자 (둘 다 만족할 때만 변환) ──
                // 구분자 행 강제: dissolve 된 raw Table 에서 사용자가 |---| 행을 지웠을 때
                // 다시 Table 로 자동 변환되며 toMarkdown() 이 |---| 를 부활시키는 회귀를 막음.
                isTableLine(line) -> {
                    // 유효한 테이블(2줄+ + 구분자) 여부를 먼저 확인 — flushText 전에
                    var j = i + 1
                    while (j < lines.size && isTableLine(lines[j])) j++
                    val tableLineCount = j - i
                    val hasSeparator = i + 1 < lines.size && lines[i + 1].contains("---")

                    if (tableLineCount >= 2 && hasSeparator) {
                        // 유효한 테이블 → flushText 후 Table 블록 생성
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
                // 현재 비활성화. 박스 UI 미구현(Phase 3 #23) 상태에서는 변환의 시각적 의미가 없고
                // focus 끊김(생성 직후) / 빈 잔류(삭제 후) 부작용만 발생.
                // ![[xxx]] 는 일반 TextBlock 텍스트로 남아 사용자가 자유롭게 편집/삭제 가능.
                // #23 진입 시 아래 분기를 복원:
                //   isEmbedLine(line) -> {
                //       flushText()
                //       val trimmed = line.trim()
                //       val target = trimmed.removePrefix("![[").removeSuffix("]]")
                //       blocks += EditorBlock.Embed(target = target)
                //       i++
                //   }

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
    // isHorizontalRule 제거 — HR은 TextBlock 인라인 렌더링으로 전환

    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
    }

    private fun isEmbedLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("![[") && trimmed.endsWith("]]")
    }
}
