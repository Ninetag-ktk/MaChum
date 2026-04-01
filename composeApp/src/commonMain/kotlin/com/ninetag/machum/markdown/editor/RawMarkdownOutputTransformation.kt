package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle

/**
 * Phase 2+3 OutputTransformation.
 *
 * - 인라인 서식: 비활성 줄에 MARKER(0.01sp 투명) + 내용 SpanStyle 적용
 * - 특수 블록: 비활성 블록 전체에 blockTransparent(정상 크기, 색상만 투명) 적용
 *   → 줄 높이 보존, 오버레이 Composable이 그 위에 배치됨
 * - 활성 줄/블록: 변환 없음 → raw 텍스트 그대로 표시
 *
 * 활성 판별:
 * - 커서가 블록 내부 → 전체 블록 활성
 * - 선택 범위가 블록과 교차 → 해당 블록 활성
 * - 그 외 → 커서 줄만 활성
 */
internal class RawMarkdownOutputTransformation(
    private val config: MarkdownStyleConfig = MarkdownStyleConfig(),
) : OutputTransformation {

    private var cachedText: String = ""
    private var cachedSpans: List<Pair<IntRange, SpanStyle>> = emptyList()
    private var cachedBlocks: List<BlockRange> = emptyList()

    /** 오버레이/DrawBehind에서 사용하는 블록 목록 */
    val blockRanges: List<BlockRange> get() = cachedBlocks

    /** 현재 활성(raw) 블록 범위들. 오버레이에서 활성 블록 숨김에 사용 */
    var activeBlockRanges: Set<IntRange> = emptySet()
        private set

    /** 단일 활성 블록 (하위 호환) */
    val activeBlockRange: IntRange? get() = activeBlockRanges.firstOrNull()

    override fun TextFieldBuffer.transformOutput() {
        val text = toString()
        if (text.isEmpty()) return

        // 텍스트가 변경된 경우에만 재스캔
        if (text != cachedText) {
            cachedText = text
            val result = MarkdownPatternScanner.scan(text, config)
            cachedSpans = result.spans
            cachedBlocks = result.blocks
        }

        // 커서/선택 범위 계산
        val selMin = minOf(selection.start, selection.end)
        val selMax = maxOf(selection.start, selection.end)
        val cursorLineStart = if (selMin == 0) 0
        else (text.lastIndexOf('\n', selMin - 1) + 1)
        val cursorLineEnd = text.indexOf('\n', selMax).let {
            if (it == -1) text.length else it
        }

        // 활성 블록 판별: 커서/선택 범위와 교차하는 모든 블록
        // blockEnd + 1: 블록 마지막 문자 바로 뒤에 커서가 있어도 활성 처리
        val activeRanges = mutableSetOf<IntRange>()
        for (block in cachedBlocks) {
            val blockStart = block.textRange.first
            val blockEnd = block.textRange.last + 1
            if (blockStart <= selMax && blockEnd >= selMin) {
                activeRanges += block.textRange
            }
        }
        activeBlockRanges = activeRanges

        // raw zone 계산: 활성 블록이 없으면 커서 줄만
        val rawZones = if (activeRanges.isNotEmpty()) {
            activeRanges.toList()
        } else {
            listOf(cursorLineStart until cursorLineEnd)
        }

        // 비활성 오버레이 블록 범위 수집 (이 범위의 인라인 스팬은 건너뜀)
        val overlayBlockRanges = cachedBlocks
            .filter { it.textRange !in activeRanges && (it.type == BlockType.CALLOUT || it.type == BlockType.TABLE) }
            .map { it.textRange }

        // 비활성 줄의 인라인 스팬 적용
        // 오버레이 블록 내부의 스팬은 건너뜀 (MARKER 0.01sp가 줄 높이를 깨뜨리므로)
        for ((range, style) in cachedSpans) {
            val inOverlayBlock = overlayBlockRanges.any { blockRange ->
                range.first >= blockRange.first && range.last <= blockRange.last
            }
            if (inOverlayBlock) continue
            applySpanOutsideRawZones(range, style, rawZones)
        }

        // 오버레이 블록 전체에 blockTransparent 적용 (정상 폰트 크기, 색상만 투명)
        for (blockRange in overlayBlockRanges) {
            val start = blockRange.first.coerceIn(0, length)
            val end = (blockRange.last + 1).coerceIn(start, length)
            if (start < end) addStyle(config.blockTransparent, start, end)
        }
    }

    /**
     * 스팬을 raw zone 외부에만 적용한다. raw zone과 겹치면 클리핑.
     */
    private fun TextFieldBuffer.applySpanOutsideRawZones(
        range: IntRange,
        style: SpanStyle,
        rawZones: List<IntRange>,
    ) {
        val spanStart = range.first
        val spanEnd = range.last // inclusive

        // 어떤 raw zone과도 겹치지 않으면 전체 적용
        val overlappingZone = rawZones.firstOrNull { zone ->
            spanStart <= zone.last && spanEnd >= zone.first
        }

        if (overlappingZone == null) {
            val start = spanStart.coerceIn(0, length)
            val end = (spanEnd + 1).coerceIn(start, length)
            if (start < end) addStyle(style, start, end)
        } else {
            // 클리핑: raw zone 전후 부분만 적용
            if (spanStart < overlappingZone.first) {
                val start = spanStart.coerceIn(0, length)
                val end = overlappingZone.first.coerceIn(start, length)
                if (start < end) addStyle(style, start, end)
            }
            val zoneEnd = overlappingZone.last + 1
            if (spanEnd >= zoneEnd) {
                val start = zoneEnd.coerceIn(0, length)
                val end = (spanEnd + 1).coerceIn(start, length)
                if (start < end) addStyle(style, start, end)
            }
        }
    }
}
