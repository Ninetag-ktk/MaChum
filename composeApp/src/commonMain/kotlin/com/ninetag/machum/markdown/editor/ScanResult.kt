package com.ninetag.machum.markdown.editor

import androidx.compose.ui.text.SpanStyle

/**
 * 블록 데코레이션이 필요한 특수 블록 타입.
 */
enum class BlockType {
    CODE_BLOCK,
    CALLOUT,
    EMBED,
}

/**
 * 특수 블록의 문서 내 범위와 메타데이터.
 *
 * @param type       블록 타입
 * @param textRange  문서 내 절대 문자 범위 (inclusive)
 * @param meta       블록별 메타데이터 (예: "calloutType" → "NOTE", "language" → "python")
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
 * @param blocks  특수 블록 범위 목록 — 블록 수준 커서 감지 + DrawBehind에서 사용
 */
data class ScanResult(
    val spans: List<Pair<IntRange, SpanStyle>>,
    val blocks: List<BlockRange>,
)
