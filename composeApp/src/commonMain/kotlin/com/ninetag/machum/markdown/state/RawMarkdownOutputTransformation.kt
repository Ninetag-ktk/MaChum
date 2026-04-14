package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

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

    /** 비활성 줄의 inline code 범위 (DrawBehind에서 RoundRect 배경 그리기용) */
    var inlineCodeRanges: List<IntRange> = emptyList()
        private set

    /**
     * BasicTextField의 포커스 여부. false이면 커서 줄 예외(raw zone)를 적용하지 않는다.
     * 오버레이 내부 에디터는 포커스 없이 시작하므로 기본값 false.
     */
    var isFocused: Boolean = false

    /**
     * true이면 오버레이 블록(Callout/Table/CodeBlock)을 항상 비활성으로 처리한다.
     * 중첩 에디터(Callout body)에서 사용: 커서 위치와 무관하게 모든 오버레이 블록을
     * blockTransparent + 오버레이 Composable로 렌더링.
     */
    var forceAllOverlaysInactive: Boolean = false

    /**
     * false이면 blockTransparent 및 높이축소를 적용하지 않는다.
     * overlayDepth >= MAX_OVERLAY_DEPTH 에서 사용: 오버레이 미생성인데
     * blockTransparent만 적용되어 텍스트가 사라지는 현상 방지.
     */
    var applyBlockTransparent: Boolean = true

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

        // 활성 블록 판별
        // 포커스 없음 → 모든 블록 비활성 (모든 오버레이 표시, 상위 컴포지션 상태와 동기화)
        // 포커스 있음 → 커서/선택 범위와 교차하는 블록만 활성
        val activeRanges = mutableSetOf<IntRange>()
        if (isFocused) {
            for (block in cachedBlocks) {
                val blockStart = block.textRange.first
                val blockEnd = block.textRange.last + 1
                if (blockStart <= selMax && blockEnd >= selMin) {
                    activeRanges += block.textRange
                }
            }
        }
        activeBlockRanges = activeRanges

        // raw zone 계산: 포커스 없으면 모든 줄 서식 적용 (raw zone 없음)
        val rawZones = if (!isFocused) {
            emptyList()
        } else if (activeRanges.isNotEmpty()) {
            activeRanges.toList()
        } else {
            listOf(cursorLineStart until cursorLineEnd)
        }

        // 비활성 오버레이 블록 범위 수집
        // applyBlockTransparent=false(오버레이 미생성)이면 비워서 blockTransparent/높이축소 스킵
        val overlayBlockTypes = setOf(BlockType.CALLOUT, BlockType.TABLE, BlockType.CODE_BLOCK)
        val overlayBlockRanges = if (!applyBlockTransparent) emptyList() else cachedBlocks
            .filter { it.textRange !in activeRanges && it.type in overlayBlockTypes }
            .map { it.textRange }

        // 비활성 줄의 인라인 스팬 적용
        // 오버레이 블록 내부의 스팬은 건너뜀 (MARKER 0.01sp가 줄 높이를 깨뜨리므로)
        // 단, calloutIndicator와 fontSize(em) 스팬은 줄 높이 맞춤 용도이므로 유지
        val codeRanges = mutableListOf<IntRange>()
        for ((range, style) in cachedSpans) {
            val inOverlayBlock = overlayBlockRanges.any { blockRange ->
                range.first >= blockRange.first && range.last <= blockRange.last
            }
            if (inOverlayBlock) {
                // 줄 높이 맞춤 용도의 스팬만 허용 (나머지는 오버레이가 렌더링 담당)
                // - calloutIndicator: ">" 인디케이터
                // - fontSize(em): body/header fontSize 보상
                // - blockTransparent: header prefix (색만 투명, 크기 유지)
                val isIndicator = style == config.calloutIndicator
                val isFontSize = style.fontSize.isEm
                val isBlockTransparent = style == config.blockTransparent
                if (!isIndicator && !isFontSize && !isBlockTransparent) continue
            }
            // inline code 범위 수집 (DrawBehind RoundRect 배경용)
            if (style == config.codeInline) {
                val inRawZone = rawZones.any { rz -> range.first >= rz.first && range.last <= rz.last }
                if (!inRawZone) codeRanges += range
            }
            applySpanOutsideRawZones(range, style, rawZones)
        }
        inlineCodeRanges = codeRanges

        // 높이 축소 대상 줄 수집: TABLE 구분자, CODE_BLOCK 펜스
        val collapseRanges = mutableListOf<IntRange>()
        for (block in cachedBlocks) {
            if (block.textRange in activeRanges) continue
            when (block.type) {
                BlockType.TABLE -> findTableSeparatorRange(text, block.textRange)?.let { collapseRanges += it }
                BlockType.CODE_BLOCK -> collapseRanges += findCodeFenceRanges(text, block.textRange)
                else -> {}
            }
        }

        // 오버레이 블록에 투명 스타일 적용
        for (blockRange in overlayBlockRanges) {
            val blockStart = blockRange.first.coerceIn(0, length)
            val blockEnd = (blockRange.last + 1).coerceIn(blockStart, length)
            if (blockStart >= blockEnd) continue

            // 축소 대상 줄이 있으면 구간 분할 적용
            val matchedCollapses = collapseRanges.filter {
                it.first >= blockRange.first && it.last <= blockRange.last
            }.sortedBy { it.first }

            if (matchedCollapses.isNotEmpty()) {
                var pos = blockStart
                for (collapse in matchedCollapses) {
                    val cStart = collapse.first.coerceIn(0, length)
                    val cEnd = (collapse.last + 1).coerceIn(cStart, length)
                    if (pos < cStart) addStyle(config.blockTransparent, pos, cStart)
                    if (cStart < cEnd) addStyle(config.marker, cStart, cEnd)
                    pos = cEnd
                }
                if (pos < blockEnd) addStyle(config.blockTransparent, pos, blockEnd)
            } else {
                addStyle(config.blockTransparent, blockStart, blockEnd)
            }
        }

    }

    /**
     * 코드 블록에서 펜스 줄(``` 으로 시작하는 줄)의 범위를 찾는다.
     * 첫 줄(여는 펜스)과 마지막 줄(닫는 펜스)을 반환한다.
     */
    private fun findCodeFenceRanges(text: String, blockRange: IntRange): List<IntRange> {
        val result = mutableListOf<IntRange>()
        val blockStart = blockRange.first
        val blockEnd = blockRange.last

        // 여는 펜스: 첫 줄
        val firstNewline = text.indexOf('\n', blockStart)
        val firstLineEnd = if (firstNewline == -1 || firstNewline > blockEnd) blockEnd else firstNewline
        result += blockStart..firstLineEnd

        // 닫는 펜스: 마지막 줄 (첫 줄과 다를 때만)
        if (firstLineEnd < blockEnd) {
            val lastNewline = text.lastIndexOf('\n', blockEnd)
            if (lastNewline > blockStart) {
                val lastLineStart = lastNewline + 1
                val lastLine = text.substring(lastLineStart, (blockEnd + 1).coerceAtMost(text.length))
                if (lastLine.trimStart().startsWith("```")) {
                    result += lastLineStart..blockEnd
                }
            }
        }

        return result
    }

    /**
     * 테이블 블록에서 구분자 줄(`| --- | --- |`)의 범위를 찾는다.
     * 구분자 줄은 헤더 줄(1번째) 바로 다음인 2번째 줄이다.
     */
    private fun findTableSeparatorRange(text: String, blockRange: IntRange): IntRange? {
        val blockStart = blockRange.first
        val blockEnd = blockRange.last
        // 첫 번째 \n 찾기 (헤더 줄 끝)
        val firstNewline = text.indexOf('\n', blockStart)
        if (firstNewline == -1 || firstNewline > blockEnd) return null
        val sepStart = firstNewline + 1
        // 두 번째 \n 찾기 (구분자 줄 끝)
        val secondNewline = text.indexOf('\n', sepStart)
        val sepEnd = if (secondNewline == -1 || secondNewline > blockEnd) blockEnd else secondNewline
        if (sepStart > sepEnd) return null
        // 구분자 줄 내용 검증 (| 와 - 로 구성)
        val sepLine = text.substring(sepStart, sepEnd)
        if (sepLine.contains('-') && sepLine.contains('|')) {
            return sepStart..sepEnd
        }
        return null
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
