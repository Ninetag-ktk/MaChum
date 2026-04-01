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
 * 활성 블록(커서가 내부에 있는 블록)은 장식을 적용하지 않는다.
 *
 * @param scrollOffset BasicTextField의 스크롤 오프셋 (px). 콘텐츠 좌표 → 뷰포트 좌표 변환에 사용.
 */
internal fun DrawScope.drawBlockDecorations(
    layout: TextLayoutResult,
    blocks: List<BlockRange>,
    activeBlockRanges: Set<IntRange>,
    config: MarkdownStyleConfig,
    scrollOffset: Float = 0f,
) {
    for (block in blocks) {
        val isActive = block.textRange in activeBlockRanges
        // 오버레이가 있는 블록 타입: 활성일 때만 DrawBehind (비활성은 오버레이가 담당)
        // 오버레이가 없는 블록 타입: 비활성일 때만 DrawBehind
        val hasOverlay = block.type == BlockType.CALLOUT || block.type == BlockType.TABLE || block.type == BlockType.CODE_BLOCK
        if (hasOverlay && !isActive) continue

        // 활성(raw 표시) 상태의 Callout/Embed/CodeBlock → 배경 없이 raw 텍스트만 표시
        if (isActive && (block.type == BlockType.CALLOUT || block.type == BlockType.EMBED || block.type == BlockType.CODE_BLOCK)) continue

        val rect = getBoundingRect(layout, block.textRange, scrollOffset) ?: continue

        // 뷰포트 밖이면 스킵 (성능 최적화)
        if (rect.bottom < 0f || rect.top > size.height) continue

        when (block.type) {
            BlockType.CODE_BLOCK -> drawCodeBlockDecoration(rect, config)
            BlockType.CALLOUT -> drawCalloutDecoration(rect, block.meta, config)
            BlockType.EMBED -> drawEmbedDecoration(rect, config)
            BlockType.HORIZONTAL_RULE -> if (!isActive) drawHorizontalRule(rect)
            BlockType.BLOCKQUOTE -> if (!isActive) drawBlockquoteLines(layout, block.textRange, config, scrollOffset)
            BlockType.TABLE -> {}
        }
    }
}

// ── CodeBlock: 라운드 배경 ──

private fun DrawScope.drawCodeBlockDecoration(rect: Rect, config: MarkdownStyleConfig) {
    val cornerRadius = CornerRadius(8.dp.toPx())
    val padding = 4.dp.toPx()
    drawRoundRect(
        color = config.codeBlockBackground,
        topLeft = Offset(0f, rect.top - padding),
        size = Size(size.width, rect.height + padding * 2),
        cornerRadius = cornerRadius,
    )
}

// ── Callout: 배경 + 왼쪽 테두리 + 아이콘 ──

private fun DrawScope.drawCalloutDecoration(
    rect: Rect,
    meta: Map<String, String>,
    config: MarkdownStyleConfig,
) {
    val calloutType = meta["calloutType"] ?: "NOTE"
    val style = config.calloutDecorationStyle(calloutType)

    val cornerRadius = CornerRadius(8.dp.toPx())
    val verticalPadding = 8.dp.toPx()
    val borderWidth = 3.dp.toPx()

    // 배경
    drawRoundRect(
        color = style.containerColor,
        topLeft = Offset(0f, rect.top - verticalPadding),
        size = Size(size.width, rect.height + verticalPadding * 2),
        cornerRadius = cornerRadius,
    )

    // 왼쪽 테두리
    drawRect(
        color = style.accentColor,
        topLeft = Offset(0f, rect.top - verticalPadding),
        size = Size(borderWidth, rect.height + verticalPadding * 2),
    )

}

// ── Blockquote: depth별 다중 왼쪽 테두리 ──

private fun DrawScope.drawBlockquoteLines(
    layout: TextLayoutResult,
    range: IntRange,
    config: MarkdownStyleConfig,
    scrollOffset: Float,
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

// ── Embed: 테두리 배경 ──

private fun DrawScope.drawEmbedDecoration(rect: Rect, config: MarkdownStyleConfig) {
    val cornerRadius = CornerRadius(6.dp.toPx())
    val padding = 2.dp.toPx()
    drawRoundRect(
        color = config.codeBlockBackground.copy(alpha = 0.5f),
        topLeft = Offset(0f, rect.top - padding),
        size = Size(size.width, rect.height + padding * 2),
        cornerRadius = cornerRadius,
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
