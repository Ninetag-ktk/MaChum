package com.ninetag.machum.markdown.editor

import androidx.compose.ui.geometry.Rect

/**
 * [BlockRange]와 해당 범위의 raw text를 파싱하여 [OverlayBlockData]를 생성한다.
 *
 * 기존 `parser/` 파이프라인을 사용하지 않고 경량 파싱으로 필요한 데이터만 추출한다.
 */
internal object OverlayBlockParser {

    private val calloutHeaderRegex = Regex("^> ?\\[!(\\w+)]\\s*(.*)")

    /**
     * 블록 타입에 따라 raw text를 파싱하여 [OverlayBlockData]를 생성한다.
     *
     * @param block     블록 범위 + 타입 정보
     * @param rawText   블록의 raw 마크다운 텍스트
     * @param rect      뷰포트 좌표 바운딩 박스
     * @return 파싱된 오버레이 데이터, 파싱 실패 시 null
     */
    fun parse(block: BlockRange, rawText: String, rect: Rect): OverlayBlockData? {
        return when (block.type) {
            BlockType.CALLOUT -> parseCallout(block, rawText, rect)
            BlockType.CODE_BLOCK -> parseCodeBlock(block, rawText, rect)
            BlockType.TABLE -> parseTable(block, rawText, rect)
            BlockType.EMBED -> null // Embed는 별도 블록으로 처리, 오버레이 아님
        }
    }

    // ── Callout ──

    private fun parseCallout(block: BlockRange, rawText: String, rect: Rect): OverlayBlockData.CalloutData? {
        val lines = rawText.split('\n')
        if (lines.isEmpty()) return null

        val headerMatch = calloutHeaderRegex.find(lines.first()) ?: return null
        val calloutType = headerMatch.groupValues[1]
        val title = headerMatch.groupValues[2]

        val bodyLines = lines.drop(1).map { line ->
            when {
                line.startsWith("> ") -> line.removePrefix("> ")
                line.startsWith(">") -> line.removePrefix(">")
                else -> line
            }
        }

        return OverlayBlockData.CalloutData(
            blockRange = block,
            viewportRect = rect,
            calloutType = calloutType,
            title = title,
            bodyLines = bodyLines,
        )
    }

    // ── CodeBlock ──

    private fun parseCodeBlock(block: BlockRange, rawText: String, rect: Rect): OverlayBlockData.CodeBlockData? {
        val lines = rawText.split('\n')
        if (lines.size < 2) return null

        val firstLine = lines.first().trim()
        val language = if (firstLine.startsWith("```")) firstLine.removePrefix("```").trim() else ""

        // 펜스 사이의 코드 내용 추출
        val codeLines = if (lines.last().trim().startsWith("```")) {
            lines.drop(1).dropLast(1)
        } else {
            lines.drop(1) // 닫히지 않은 코드 블록
        }

        return OverlayBlockData.CodeBlockData(
            blockRange = block,
            viewportRect = rect,
            language = language,
            code = codeLines.joinToString("\n"),
        )
    }

    // ── Table ──

    private fun parseTable(block: BlockRange, rawText: String, rect: Rect): OverlayBlockData.TableData? {
        val lines = rawText.split('\n').filter { it.isNotBlank() }
        if (lines.size < 2) return null

        fun parseCells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|")
                .split("|")
                .map { it.trim() }

        val headers = parseCells(lines.first())

        // 구분자 줄(|---|---| 등) 건너뛰기
        val dataStartIndex = if (lines.size > 1 && lines[1].contains("---")) 2 else 1
        val rows = lines.drop(dataStartIndex).map { parseCells(it) }

        return OverlayBlockData.TableData(
            blockRange = block,
            viewportRect = rect,
            headers = headers,
            rows = rows,
        )
    }
}
