package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.state.VisualTaskCheckbox

/**
 * BasicTextField의 drawBehind에서 호출하여 블록 데코레이션을 그린다.
 *
 * v2 블록 에디터에서 TextBlockEditor 가 호출. BLOCKQUOTE 좌측 바, HORIZONTAL_RULE 구분선,
 * 인라인 코드 RoundRect 외곽선만 그린다.
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
    taskCheckboxes: List<VisualTaskCheckbox> = emptyList(),
) {
    for (block in blocks) {
        val rect = getBoundingRect(layout, block.textRange, scrollOffset) ?: continue

        // 뷰포트 밖이면 스킵 (성능 최적화)
        if (rect.bottom < 0f || rect.top > size.height) continue

        when (block.type) {
            BlockType.HORIZONTAL_RULE -> drawHorizontalRule(rect, config.horizontalRuleColor)
            BlockType.BLOCKQUOTE -> drawBlockquoteLines(layout, block.textRange, config, scrollOffset, rawZones)
        }
    }

    // ── Inline Code: RoundRect 외곽선 ──
    drawInlineCodeOutlines(layout, inlineCodeRanges, config, scrollOffset)
    drawTaskCheckboxes(layout, taskCheckboxes, config, scrollOffset)
}

private fun DrawScope.drawTaskCheckboxes(
    layout: TextLayoutResult,
    checkboxes: List<VisualTaskCheckbox>,
    config: MarkdownStyleConfig,
    scrollOffset: Float,
) {
    val size = MarkdownEditorStyleTokens.taskCheckboxSize.toPx()
    val stroke = 1.4.dp.toPx()
    val color = config.bulletPrefix.color
    for (checkbox in checkboxes) {
        val bounds = layout.getBoundingBox(checkbox.range.first)
        val top = layout.getLineTop(layout.getLineForOffset(checkbox.range.first)) - scrollOffset
        val bottom = layout.getLineBottom(layout.getLineForOffset(checkbox.range.first)) - scrollOffset
        if (bottom < 0f || top > this.size.height) continue
        val left = (bounds.left + bounds.right - size) / 2f
        val boxTop = (top + bottom - size) / 2f
        drawRect(color, Offset(left, boxTop), Size(size, size), style = Stroke(stroke))
        if (checkbox.checked) {
            drawLine(color, Offset(left + size * 0.18f, boxTop + size * 0.52f), Offset(left + size * 0.42f, boxTop + size * 0.76f), stroke)
            drawLine(color, Offset(left + size * 0.42f, boxTop + size * 0.76f), Offset(left + size * 0.84f, boxTop + size * 0.24f), stroke)
        }
    }
}

// ── Inline Code: RoundRect 외곽선 ──

private fun DrawScope.drawInlineCodeOutlines(
    layout: TextLayoutResult,
    ranges: List<IntRange>,
    config: MarkdownStyleConfig,
    scrollOffset: Float,
) {
    if (ranges.isEmpty()) return
    val textLen = layout.layoutInput.text.length
    val inlineCode = config.inlineCode
    val cornerRadius = CornerRadius(inlineCode.cornerRadius.toPx())
    val horizontalPadding = inlineCode.horizontalPadding.toPx()
    val borderWidth = inlineCode.borderWidth.toPx()
    val halfBorder = borderWidth / 2f
    val stroke = Stroke(width = borderWidth)

    for (range in ranges) {
        val safeStart = range.first.coerceIn(0, textLen)
        val safeEnd = (range.last + 1).coerceIn(safeStart, textLen)
        if (safeStart >= safeEnd) continue

        val startLine = layout.getLineForOffset(safeStart)
        val endLine = layout.getLineForOffset(safeEnd - 1)

        for (line in startLine..endLine) {
            val rawLeft = if (line == startLine) {
                layout.getHorizontalPosition(safeStart, true)
            } else {
                layout.getLineLeft(line)
            }
            val rawRight = if (line == endLine) {
                layout.getHorizontalPosition(safeEnd, true)
            } else {
                layout.getLineRight(line)
            }
            val left = minOf(rawLeft, rawRight) - horizontalPadding + halfBorder
            val right = maxOf(rawLeft, rawRight) + horizontalPadding - halfBorder
            val lineTop = layout.getLineTop(line) - scrollOffset
            val lineBottom = layout.getLineBottom(line) - scrollOffset
            if (lineBottom < 0f || lineTop > size.height) continue
            drawRoundRect(
                color = inlineCode.borderColor,
                topLeft = Offset(left, lineTop + halfBorder),
                size = Size(
                    (right - left).coerceAtLeast(0f),
                    lineBottom - lineTop - borderWidth,
                ),
                cornerRadius = cornerRadius,
                style = stroke,
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

private fun DrawScope.drawHorizontalRule(rect: Rect, color: androidx.compose.ui.graphics.Color) {
    val y = rect.top + rect.height / 2
    drawLine(
        color = color,
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
