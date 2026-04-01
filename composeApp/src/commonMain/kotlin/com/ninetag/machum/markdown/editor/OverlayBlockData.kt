package com.ninetag.machum.markdown.editor

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
