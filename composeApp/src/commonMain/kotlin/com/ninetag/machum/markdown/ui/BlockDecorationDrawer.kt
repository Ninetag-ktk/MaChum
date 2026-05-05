package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

/**
 * BasicTextField의 drawBehind에서 호출하여 블록 데코레이션을 그린다.
 *
 * v2 블록 에디터에서 TextBlockEditor 가 호출. BLOCKQUOTE 좌측 바, HORIZONTAL_RULE 구분선,
 * 인라인 코드 RoundRect 배경만 그린다.
 *
 * @param scrollOffset BasicTextField의 스크롤 오프셋 (px). 콘텐츠 좌표 → 뷰포트 좌표 변환에 사용.
 */
internal fun DrawScope.drawBlockDecorations(
    layout: TextLayoutResult,
    blocks: List<BlockRange>,
    config: MarkdownStyleConfig,
    scrollOffset: Float = 0f,
    inlineCodeRanges: List<IntRange> = emptyList(),
    rawZones: List<IntRange> = emptyList(),
) {
    for (block in blocks) {
        val rect = getBoundingRect(layout, block.textRange, scrollOffset) ?: continue

        // 뷰포트 밖이면 스킵 (성능 최적화)
        if (rect.bottom < 0f || rect.top > size.height) continue

        when (block.type) {
            BlockType.HORIZONTAL_RULE -> drawHorizontalRule(rect)
            BlockType.BLOCKQUOTE -> drawBlockquoteLines(layout, block.textRange, config, scrollOffset, rawZones)
        }
    }

    // ── Inline Code: RoundRect 배경 ──
    drawInlineCodeBackgrounds(layout, inlineCodeRanges, config, scrollOffset)
}

// ── Inline Code: RoundRect 배경 ──

private fun DrawScope.drawInlineCodeBackgrounds(
    layout: TextLayoutResult,
    ranges: List<IntRange>,
    config: MarkdownStyleConfig,
    scrollOffset: Float,
) {
    if (ranges.isEmpty()) return
    val textLen = layout.layoutInput.text.length
    val cornerRadius = CornerRadius(4.dp.toPx())
    val verticalPadding = 0f
    val horizontalPadding = 2.dp.toPx()

    for (range in ranges) {
        val safeStart = range.first.coerceIn(0, textLen)
        val safeEnd = (range.last + 1).coerceIn(safeStart, textLen)
        if (safeStart >= safeEnd) continue

        val startLine = layout.getLineForOffset(safeStart)
        val endLine = layout.getLineForOffset(safeEnd - 1)

        // 단일 줄: 문자 범위의 좌표로 정확한 배경
        if (startLine == endLine) {
            val left = layout.getHorizontalPosition(safeStart, true)
            val right = layout.getHorizontalPosition(safeEnd, true)
            val top = layout.getLineTop(startLine) - scrollOffset
            val bottom = layout.getLineBottom(startLine) - scrollOffset
            if (bottom < 0f || top > size.height) continue
            drawRoundRect(
                color = config.codeInlineBackground,
                topLeft = Offset(left - horizontalPadding, top - verticalPadding),
                size = Size(right - left + horizontalPadding * 2, bottom - top + verticalPadding * 2),
                cornerRadius = cornerRadius,
            )
        } else {
            // 여러 줄에 걸치는 경우: 줄 전체 폭으로
            val top = layout.getLineTop(startLine) - scrollOffset
            val bottom = layout.getLineBottom(endLine) - scrollOffset
            if (bottom < 0f || top > size.height) continue
            drawRoundRect(
                color = config.codeInlineBackground,
                topLeft = Offset(0f, top - verticalPadding),
                size = Size(size.width, bottom - top + verticalPadding * 2),
                cornerRadius = cornerRadius,
            )
        }
    }
}

// ── Blockquote: depth별 다중 왼쪽 테두리 ──

private fun DrawScope.drawBlockquoteLines(
    layout: TextLayoutResult,
    range: IntRange,
    config: MarkdownStyleConfig,
    scrollOffset: Float,
    rawZones: List<IntRange> = emptyList(),
) {
    val text = layout.layoutInput.text.toString()
    val textLen = text.length
    val borderWidth = 3.dp.toPx()
    val borderSpacing = 8.dp.toPx()

    var lineStart = range.first.coerceIn(0, textLen)
    val rangeEnd = range.last.coerceIn(0, textLen)

    while (lineStart <= rangeEnd) {
        val lineEnd = text.indexOf('\n', lineStart).let {
            if (it == -1 || it > rangeEnd) rangeEnd else it
        }

        // raw zone 안의 줄은 좌측 바 skip (raw 마커가 보이는 상태에서 시각 충돌 방지)
        val isInRawZone = rawZones.any { it.first <= lineEnd && it.last >= lineStart }
        if (isInRawZone) {
            lineStart = lineEnd + 1
            if (lineStart > rangeEnd) break
            continue
        }

        // depth 계산: 연속 > 문자 수
        var depth = 0
        var pos = lineStart
        while (pos <= lineEnd && pos < textLen && text[pos] == '>') {
            depth++
            pos++
            if (pos <= lineEnd && pos < textLen && text[pos] == ' ') pos++
        }

        if (depth > 0) {
            val safeLine = lineStart.coerceIn(0, textLen - 1)
            val layoutLine = layout.getLineForOffset(safeLine)
            val lineTop = layout.getLineTop(layoutLine) - scrollOffset
            val lineBottom = layout.getLineBottom(layoutLine) - scrollOffset

            if (lineBottom >= 0f && lineTop <= size.height) {
                for (d in 0 until depth) {
                    val x = d * borderSpacing
                    drawRect(
                        color = config.blockquoteAccent,
                        topLeft = Offset(x, lineTop),
                        size = Size(borderWidth, lineBottom - lineTop),
                    )
                }
            }
        }

        lineStart = lineEnd + 1
        if (lineStart > rangeEnd) break
    }
}

// ── HorizontalRule: 구분선 ──

private fun DrawScope.drawHorizontalRule(rect: Rect) {
    val y = rect.top + rect.height / 2
    drawLine(
        color = Color(0x33000000),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
    )
}

// ── 유틸리티 ──

/**
 * TextLayoutResult에서 주어진 문자 범위의 바운딩 박스를 계산한다.
 * 스크롤 오프셋을 적용하여 뷰포트 좌표로 변환한다.
 */
private fun getBoundingRect(layout: TextLayoutResult, range: IntRange, scrollOffset: Float): Rect? {
    if (range.isEmpty()) return null
    val textLen = layout.layoutInput.text.length
    if (textLen == 0) return null
    val safeFirst = range.first.coerceIn(0, textLen - 1)
    val safeLast = range.last.coerceIn(0, textLen - 1)
    if (safeFirst > safeLast) return null

    val firstLine = layout.getLineForOffset(safeFirst)
    val lastLine = layout.getLineForOffset(safeLast)
    val top = layout.getLineTop(firstLine) - scrollOffset
    val bottom = layout.getLineBottom(lastLine) - scrollOffset
    return Rect(0f, top, layout.size.width.toFloat(), bottom)
}
