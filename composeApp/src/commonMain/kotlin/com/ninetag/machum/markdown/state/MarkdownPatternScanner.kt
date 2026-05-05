package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

import androidx.compose.ui.text.SpanStyle

/**
 * 블록 데코레이션이 필요한 특수 블록 타입.
 *
 * v2 블록 에디터에서는 TextBlock 콘텐츠 내부 데코레이션만 다룬다.
 * Callout/Code/Table 은 각각 전용 Composable 이 처리하므로 본 스캐너는 다루지 않는다.
 */
enum class BlockType {
    HORIZONTAL_RULE,
    BLOCKQUOTE,
}

/**
 * 특수 블록의 문서 내 범위와 메타데이터.
 *
 * @param type       블록 타입
 * @param textRange  문서 내 절대 문자 범위 (inclusive)
 * @param meta       블록별 메타데이터
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
 * @param blocks  특수 블록 범위 목록 — DrawBehind 에서 사용 (BLOCKQUOTE, HORIZONTAL_RULE)
 */
data class ScanResult(
    val spans: List<Pair<IntRange, SpanStyle>>,
    val blocks: List<BlockRange>,
)

/**
 * TextBlock 의 raw 텍스트를 스캔하여 인라인 서식 spans 와 데코레이션 blocks 를 반환한다.
 * 기호를 제거하지 않고, 기호 범위(MARKER)와 내용 범위(SpanStyle)만 알려준다.
 *
 * v2 블록 에디터에서 TextBlockEditor 의 OutputTransformation 이 호출.
 */
internal object MarkdownPatternScanner {

    /**
     * @param text   TextBlock 의 raw 텍스트
     * @param config 서식 스타일 설정
     * @return [ScanResult] — 인라인 서식 + 데코레이션 블록 범위
     */
    fun scan(text: String, config: MarkdownStyleConfig): ScanResult {
        if (text.isEmpty()) return ScanResult(emptyList(), emptyList())

        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        val blocks = mutableListOf<BlockRange>()
        val lines = text.split('\n')
        var i = 0
        var offset = 0

        // 블록 prefix 없는 연속 일반 텍스트 줄 그룹핑 (멀티라인 인라인 서식 지원)
        var groupStart = -1
        val groupText = StringBuilder()

        while (i < lines.size) {
            val line = lines[i]

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
                    line.startsWith(">") -> {
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
                        while (j < lines.size && lines[j].startsWith(">")) {
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
}
