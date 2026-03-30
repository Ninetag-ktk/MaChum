package com.ninetag.machum.markdown.editor

import androidx.compose.ui.text.SpanStyle
import com.ninetag.machum.markdown.token.MarkdownBlock

/**
 * 문서 전체의 raw 텍스트를 스캔하여 서식 범위 목록을 반환한다.
 * 기호를 제거하지 않고, 기호 범위(MARKER)와 내용 범위(SpanStyle)만 알려준다.
 *
 * Phase 2 (raw text 방식)의 핵심 컴포넌트.
 * MarkdownStyleProcessor 를 대체한다.
 */
internal object MarkdownPatternScanner {

    /**
     * @param text   문서 전체 raw 텍스트
     * @param config 서식 스타일 설정
     * @return (범위, SpanStyle) 쌍 목록.
     *         MARKER SpanStyle → 투명 처리 대상 (서식 기호).
     *         기타 SpanStyle   → 서식 적용 대상 (내용).
     */
    fun scan(text: String, config: MarkdownStyleConfig): List<Pair<IntRange, SpanStyle>> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<Pair<IntRange, SpanStyle>>()
        val lines = text.split('\n')
        var offset = 0
        var inCodeBlock = false
        var codeBlockStart = 0
        val codeBlockLines = mutableListOf<String>()

        // 블록 prefix 없는 연속 일반 텍스트 줄 그룹핑 (멀티라인 인라인 서식 지원)
        var groupStart = -1
        val groupText = StringBuilder()

        for (line in lines) {
            val isFence = line.trimStart().startsWith("```")

            if (isFence && !inCodeBlock) {
                flushGroup(groupText, groupStart, result, config)
                groupStart = -1
                inCodeBlock = true
                codeBlockStart = offset
                codeBlockLines.clear()
                codeBlockLines.add(line)
                offset += line.length + 1
                continue
            }

            if (isFence && inCodeBlock) {
                codeBlockLines.add(line)
                result += InlineStyleScanner.computeSpans(
                    MarkdownBlock.CodeBlock("", "", 0, 0),
                    codeBlockLines.joinToString("\n"),
                    codeBlockStart,
                    config,
                )
                inCodeBlock = false
                offset += line.length + 1
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                offset += line.length + 1
                continue
            }

            if (line.isNotEmpty()) {
                val headingLevel = detectHeadingLevel(line)
                when {
                    headingLevel > 0 -> {
                        flushGroup(groupText, groupStart, result, config)
                        groupStart = -1
                        result += InlineStyleScanner.computeSpans(
                            MarkdownBlock.Heading(headingLevel, emptyList()), line, offset, config,
                        )
                    }
                    hasBlockPrefix(line) -> {
                        flushGroup(groupText, groupStart, result, config)
                        groupStart = -1
                        result += InlineStyleScanner.computeSpans(
                            MarkdownBlock.TextBlock(emptyList()), line, offset, config,
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
                flushGroup(groupText, groupStart, result, config)
                groupStart = -1
            }

            offset += line.length + 1
        }

        // 남은 그룹 처리
        flushGroup(groupText, groupStart, result, config)

        // 닫히지 않은 코드 블록 처리
        if (inCodeBlock) {
            result += InlineStyleScanner.computeSpans(
                MarkdownBlock.CodeBlock("", "", 0, 0),
                codeBlockLines.joinToString("\n"),
                codeBlockStart,
                config,
            )
        }

        return result
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
                MarkdownBlock.TextBlock(emptyList()), text, groupStart, config,
            )
        }
        groupText.clear()
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
        if (line.startsWith("> ") || line == ">") return true

        var indent = 0
        while (indent < line.length && (line[indent] == ' ' || line[indent] == '\t')) indent++
        if (indent >= line.length) return false
        val rest = line.substring(indent)

        if (rest.startsWith("- ") || rest.startsWith("* ")) return true

        // Ordered list: "숫자. "
        var i = 0
        while (i < rest.length && rest[i].isDigit()) i++
        if (i > 0 && i + 1 < rest.length && rest[i] == '.' && rest[i + 1] == ' ') return true

        return false
    }
}
