package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

import androidx.compose.ui.text.SpanStyle

/**
 * 블록 데코레이션이 필요한 특수 블록 타입.
 */
enum class BlockType {
    CODE_BLOCK,
    CALLOUT,
    EMBED,
    TABLE,
    HORIZONTAL_RULE,
    BLOCKQUOTE,
}

/**
 * 특수 블록의 문서 내 범위와 메타데이터.
 *
 * @param type       블록 타입
 * @param textRange  문서 내 절대 문자 범위 (inclusive)
 * @param meta       블록별 메타데이터 (예: "calloutType" → "NOTE", "language" → "python")
 */
data class BlockRange(
    val type: BlockType,
    val textRange: IntRange,
    val meta: Map<String, String> = emptyMap(),
)

/**
 * [MarkdownPatternScanner]의 스캔 결과.
 *
 * @param spans   (문서 내 범위, SpanStyle) 쌍 목록 — OutputTransformation에서 사용
 * @param blocks  특수 블록 범위 목록 — 블록 수준 커서 감지 + DrawBehind에서 사용
 */
data class ScanResult(
    val spans: List<Pair<IntRange, SpanStyle>>,
    val blocks: List<BlockRange>,
)

/**
 * 문서 전체의 raw 텍스트를 스캔하여 서식 범위 목록과 블록 범위 목록을 반환한다.
 * 기호를 제거하지 않고, 기호 범위(MARKER)와 내용 범위(SpanStyle)만 알려준다.
 *
 * Phase 2 (raw text 방식)의 핵심 컴포넌트.
 */
internal object MarkdownPatternScanner {

    private val calloutHeaderRegex = Regex("^(>+) ?\\[!(\\w+)]\\s*")

    /**
     * @param text   문서 전체 raw 텍스트
     * @param config 서식 스타일 설정
     * @return [ScanResult] — 서식 범위 + 블록 범위
     */
    fun scan(text: String, config: MarkdownStyleConfig): ScanResult {
        if (text.isEmpty()) return ScanResult(emptyList(), emptyList())

        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        val blocks = mutableListOf<BlockRange>()
        val lines = text.split('\n')
        var i = 0
        var offset = 0
        var inCodeBlock = false
        var codeBlockStart = 0
        val codeBlockLines = mutableListOf<String>()

        // 블록 prefix 없는 연속 일반 텍스트 줄 그룹핑 (멀티라인 인라인 서식 지원)
        var groupStart = -1
        val groupText = StringBuilder()

        while (i < lines.size) {
            val line = lines[i]
            val isFence = line.trimStart().startsWith("```")

            // ── 코드 블록 시작 ──
            if (isFence && !inCodeBlock) {
                flushGroup(groupText, groupStart, spans, config)
                groupStart = -1
                inCodeBlock = true
                codeBlockStart = offset
                codeBlockLines.clear()
                codeBlockLines.add(line)
                offset += line.length + 1
                i++
                continue
            }

            // ── 코드 블록 종료 ──
            if (isFence && inCodeBlock) {
                codeBlockLines.add(line)
                val blockText = codeBlockLines.joinToString("\n")
                spans += InlineStyleScanner.computeSpans(
                    MarkdownBlock.CodeBlock(),
                    blockText, codeBlockStart, config,
                )
                blocks += BlockRange(
                    type = BlockType.CODE_BLOCK,
                    textRange = codeBlockStart until (offset + line.length),
                )
                inCodeBlock = false
                offset += line.length + 1
                i++
                continue
            }

            // ── 코드 블록 내부 ──
            if (inCodeBlock) {
                codeBlockLines.add(line)
                offset += line.length + 1
                i++
                continue
            }

            // ── Callout 감지: "> [!TYPE]" 패턴 ──
            if (calloutHeaderRegex.containsMatchIn(line)) {
                flushGroup(groupText, groupStart, spans, config)
                groupStart = -1

                val calloutStart = offset
                val calloutLines = mutableListOf(line)
                val headerMatch = calloutHeaderRegex.find(line)
                val calloutDepth = headerMatch?.groupValues?.get(1)?.length ?: 1
                val calloutType = headerMatch?.groupValues?.get(2) ?: "NOTE"

                var j = i + 1
                var calloutOffset = offset + line.length + 1
                // 후속 ">" 줄을 소비 (같은 depth 이하의 새 Callout 헤더가 나오면 중단)
                while (j < lines.size) {
                    val nextLine = lines[j]
                    if (!nextLine.startsWith(">")) break
                    val nextMatch = calloutHeaderRegex.find(nextLine)
                    if (nextMatch != null) {
                        val nextDepth = nextMatch.groupValues[1].length
                        if (nextDepth <= calloutDepth) break
                    }
                    calloutLines.add(nextLine)
                    calloutOffset += nextLine.length + 1
                    j++
                }

                val calloutText = calloutLines.joinToString("\n")
                val calloutEnd = calloutStart + calloutText.length

                spans += InlineStyleScanner.calloutSpans(calloutText, calloutStart, config, depth = calloutDepth, calloutType = calloutType)
                // body 줄이 있을 때만 CALLOUT 블록(오버레이 대상)으로 등록
                // 헤더만 있는 Callout은 인라인 스타일로만 렌더링
                if (calloutLines.size >= 2) {
                    blocks += BlockRange(
                        type = BlockType.CALLOUT,
                        textRange = calloutStart until calloutEnd,
                        meta = mapOf(
                            "calloutType" to calloutType,
                            "depth" to calloutDepth.toString(),
                        ),
                    )
                }

                offset = calloutOffset
                i = j
                continue
            }

            // ── 일반 줄 처리 (기존 로직) ──
            if (line.isNotEmpty()) {
                val headingLevel = detectHeadingLevel(line)
                when {
                    headingLevel > 0 -> {
                        flushGroup(groupText, groupStart, spans, config)
                        groupStart = -1
                        spans += InlineStyleScanner.computeSpans(
                            MarkdownBlock.Heading(headingLevel), line, offset, config,
                        )
                    }
                    isHorizontalRule(line) -> {
                        flushGroup(groupText, groupStart, spans, config)
                        groupStart = -1
                        spans += InlineStyleScanner.computeSpans(
                            MarkdownBlock.HorizontalRule, line, offset, config,
                        )
                        blocks += BlockRange(
                            type = BlockType.HORIZONTAL_RULE,
                            textRange = offset until (offset + line.length),
                        )
                    }
                    isBlockquoteLine(line) -> {
                        flushGroup(groupText, groupStart, spans, config)
                        groupStart = -1
                        // 연속 > 줄 그룹화
                        val bqStart = offset
                        spans += InlineStyleScanner.computeSpans(
                            MarkdownBlock.TextBlock, line, offset, config,
                        )
                        var bqEnd = offset + line.length
                        var j = i + 1
                        var bqOffset = offset + line.length + 1
                        while (j < lines.size && isBlockquoteLine(lines[j])) {
                            spans += InlineStyleScanner.computeSpans(
                                MarkdownBlock.TextBlock, lines[j], bqOffset, config,
                            )
                            bqEnd = bqOffset + lines[j].length
                            bqOffset += lines[j].length + 1
                            j++
                        }
                        blocks += BlockRange(
                            type = BlockType.BLOCKQUOTE,
                            textRange = bqStart until bqEnd,
                        )
                        offset = bqOffset
                        i = j
                        continue
                    }
                    hasBlockPrefix(line) -> {
                        flushGroup(groupText, groupStart, spans, config)
                        groupStart = -1
                        spans += InlineStyleScanner.computeSpans(
                            MarkdownBlock.TextBlock, line, offset, config,
                        )
                    }
                    isEmbedLine(line) -> {
                        flushGroup(groupText, groupStart, spans, config)
                        groupStart = -1
                        spans += InlineStyleScanner.computeSpans(
                            MarkdownBlock.Embed(), line, offset, config,
                        )
                        blocks += BlockRange(
                            type = BlockType.EMBED,
                            textRange = offset until (offset + line.length),
                        )
                    }
                    isTableLine(line) -> {
                        flushGroup(groupText, groupStart, spans, config)
                        groupStart = -1
                        // Table: 연속 | 줄 소비
                        val tableStart = offset
                        val tableLines = mutableListOf(line)
                        var j = i + 1
                        var tableOffset = offset + line.length + 1
                        while (j < lines.size && isTableLine(lines[j])) {
                            tableLines.add(lines[j])
                            tableOffset += lines[j].length + 1
                            j++
                        }
                        // 최소 2줄(헤더 + 구분자) 이상이면 Table 블록
                        if (tableLines.size >= 2) {
                            val tableText = tableLines.joinToString("\n")
                            blocks += BlockRange(
                                type = BlockType.TABLE,
                                textRange = tableStart until (tableStart + tableText.length),
                            )
                            // Table 내부는 인라인 스캔 (오버레이가 담당하므로 기본 처리)
                            spans += InlineStyleScanner.computeSpans(
                                MarkdownBlock.Table(), tableText, tableStart, config,
                            )
                        }
                        offset = tableOffset
                        i = j
                        continue
                    }
                    else -> {
                        // 블록 prefix 없는 일반 텍스트 → 그룹에 추가
                        if (groupStart == -1) {
                            groupStart = offset
                        } else {
                            groupText.append('\n')
                        }
                        groupText.append(line)
                    }
                }
            } else {
                // 빈 줄은 그룹을 끊음
                flushGroup(groupText, groupStart, spans, config)
                groupStart = -1
            }

            offset += line.length + 1
            i++
        }

        // 남은 그룹 처리
        flushGroup(groupText, groupStart, spans, config)

        // 닫히지 않은 코드 블록 처리
        if (inCodeBlock) {
            val blockText = codeBlockLines.joinToString("\n")
            spans += InlineStyleScanner.computeSpans(
                MarkdownBlock.CodeBlock(),
                blockText, codeBlockStart, config,
            )
            blocks += BlockRange(
                type = BlockType.CODE_BLOCK,
                textRange = codeBlockStart until (codeBlockStart + blockText.length),
            )
        }

        return ScanResult(spans, blocks)
    }

    /** 그룹핑된 텍스트 줄을 스캔하여 결과에 추가. 2줄 이상이면 멀티라인 스캔. */
    private fun flushGroup(
        groupText: StringBuilder,
        groupStart: Int,
        result: MutableList<Pair<IntRange, SpanStyle>>,
        config: MarkdownStyleConfig,
    ) {
        if (groupText.isEmpty()) return
        val text = groupText.toString()
        if (text.contains('\n')) {
            result += InlineStyleScanner.computeMultiLineSpans(text, groupStart, config)
        } else {
            result += InlineStyleScanner.computeSpans(
                MarkdownBlock.TextBlock, text, groupStart, config,
            )
        }
        groupText.clear()
    }

    /** 줄이 수평선(`---`, `***`, `___` 3개 이상)인지 판별 */
    private fun isHorizontalRule(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val ch = trimmed[0]
        if (ch != '-' && ch != '*' && ch != '_') return false
        return trimmed.all { it == ch || it == ' ' }
    }

    private fun detectHeadingLevel(line: String): Int {
        var level = 0
        while (level < line.length && line[level] == '#') level++
        if (level == 0 || level > 6) return 0
        if (level >= line.length || line[level] != ' ') return 0
        return level
    }

    /** 줄이 blockquote(> )로 시작하는지 판별 (Callout 아닌) */
    private fun isBlockquoteLine(line: String): Boolean =
        line.startsWith(">") && !calloutHeaderRegex.containsMatchIn(line)

    /** 줄이 블록 레벨 prefix(>, -, *, 숫자.)로 시작하는지 판별 */
    private fun hasBlockPrefix(line: String): Boolean {
        if (line.startsWith(">")) return true

        var indent = 0
        while (indent < line.length && (line[indent] == ' ' || line[indent] == '\t')) indent++
        if (indent >= line.length) return false
        val rest = line.substring(indent)

        if (rest.startsWith("- ") || rest.startsWith("* ")) return true

        // Ordered list: "숫자. "
        var j = 0
        while (j < rest.length && rest[j].isDigit()) j++
        if (j > 0 && j + 1 < rest.length && rest[j] == '.' && rest[j + 1] == ' ') return true

        return false
    }

    /** 줄이 임베드 링크인지 판별 */
    private fun isEmbedLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("![[") && trimmed.endsWith("]]")
    }

    /** 줄이 테이블 행인지 판별 (| 로 시작하고 | 를 2개 이상 포함) */
    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
    }
}
