package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult

/**
 * [TextLayoutResult]의 문자 오프셋을 뷰포트 좌표의 [Rect]로 변환한다.
 *
 * [BlockDecorationDrawer]의 DrawScope 전용 좌표 계산을 Composable 컨텍스트에서도
 * 사용할 수 있도록 독립 유틸리티로 추출한 것이다.
 */
internal object OverlayPositionCalculator {

    /**
     * 주어진 문자 범위의 바운딩 박스를 뷰포트 좌표로 계산한다.
     *
     * @param layout        BasicTextField의 TextLayoutResult
     * @param range         문서 내 절대 문자 범위 (inclusive)
     * @param scrollOffset  스크롤 오프셋 (px)
     * @return 뷰포트 좌표의 Rect, 범위가 비어있거나 유효하지 않으면 null
     */
    fun compute(layout: TextLayoutResult, range: IntRange, scrollOffset: Float): Rect? {
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

    /**
     * 뷰포트 높이 내에 보이는 블록인지 판별한다.
     */
    fun isVisible(rect: Rect, viewportHeight: Float): Boolean =
        rect.bottom > 0f && rect.top < viewportHeight
}
