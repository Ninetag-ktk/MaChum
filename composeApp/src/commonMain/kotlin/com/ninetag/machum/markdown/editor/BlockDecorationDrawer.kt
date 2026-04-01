package com.ninetag.machum.markdown.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
        val hasOverlay = block.type == BlockType.CALLOUT || block.type == BlockType.TABLE
        if (hasOverlay && !isActive) continue

        // 활성(raw 표시) 상태의 Callout/Embed → 배경 없이 raw 텍스트만 표시
        if (isActive && (block.type == BlockType.CALLOUT || block.type == BlockType.EMBED)) continue

        val rect = getBoundingRect(layout, block.textRange, scrollOffset) ?: continue

        // 뷰포트 밖이면 스킵 (성능 최적화)
        if (rect.bottom < 0f || rect.top > size.height) continue

        when (block.type) {
            BlockType.CODE_BLOCK -> drawCodeBlockDecoration(rect, config)
            BlockType.CALLOUT -> drawCalloutDecoration(rect, block.meta, config)
            BlockType.EMBED -> drawEmbedDecoration(rect, config)
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
