package com.ninetag.machum.markdown.state

import androidx.compose.ui.geometry.Rect

/**
 * 오버레이 Composable에 필요한 파싱된 블록 데이터.
 *
 * [BlockRange]는 텍스트 범위만 알려주고, 이 클래스는 실제 콘텐츠(제목, 셀 등)를 포함한다.
 * [OverlayBlockParser]가 raw text에서 파싱하여 생성한다.
 */
sealed class OverlayBlockData {
    /** 원본 블록 범위 (raw text 오프셋) */
    abstract val blockRange: BlockRange
    /** 뷰포트 좌표계의 바운딩 박스 (scrollOffset 보정 완료) */
    abstract val viewportRect: Rect

    data class CalloutData(
        override val blockRange: BlockRange,
        override val viewportRect: Rect,
        val calloutType: String,
        val title: String,
        val bodyLines: List<String>,
    ) : OverlayBlockData()

    data class TableData(
        override val blockRange: BlockRange,
        override val viewportRect: Rect,
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : OverlayBlockData()

    data class CodeBlockData(
        override val blockRange: BlockRange,
        override val viewportRect: Rect,
        val language: String,
        val code: String,
    ) : OverlayBlockData()
}

/**
 * [BlockRange]와 해당 범위의 raw text를 파싱하여 [OverlayBlockData]를 생성한다.
 *
 * 경량 파싱으로 오버레이에 필요한 데이터만 추출한다.
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
            BlockType.CODE_BLOCK -> null // raw 마크다운 그대로 표시
            BlockType.TABLE -> parseTable(block, rawText, rect)
            BlockType.EMBED -> null // Embed는 별도 블록으로 처리, 오버레이 아님
            BlockType.HORIZONTAL_RULE -> null // DrawBehind로 처리
            BlockType.BLOCKQUOTE -> null // DrawBehind로 처리
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
