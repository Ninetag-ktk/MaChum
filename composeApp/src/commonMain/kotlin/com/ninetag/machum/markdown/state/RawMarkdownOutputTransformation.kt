package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle

/**
 * TextBlock 의 OutputTransformation.
 *
 * - 비활성 줄: MARKER(0.01sp 투명) + 내용 SpanStyle 적용
 * - 활성 줄(커서 줄): 문법 마커는 raw로 노출하고 내용 SpanStyle은 즉시 적용
 * - dissolve rawMode: 마커와 내용 스타일을 모두 적용하지 않는 완전 raw 표시
 *
 * 활성 판별: 커서가 위치한 줄만 raw 표시. 그 외 모든 줄은 서식 적용.
 * 포커스 없으면 모든 줄에 서식 적용 (raw zone 없음).
 */
internal class RawMarkdownOutputTransformation(
    private val config: MarkdownStyleConfig = MarkdownStyleConfig(),
) : OutputTransformation {

    private var cachedText: String = ""
    private var cachedSpans: List<Pair<IntRange, SpanStyle>> = emptyList()
    private var cachedBlocks: List<BlockRange> = emptyList()

    /** DrawBehind 에서 사용하는 데코레이션 블록 목록 (BLOCKQUOTE, HORIZONTAL_RULE) */
    val blockRanges: List<BlockRange> get() = cachedBlocks

    /** rawMode가 아닌 줄의 inline code 범위 (DrawBehind 에서 RoundRect 배경 그리기용) */
    var inlineCodeRanges: List<IntRange> = emptyList()
        private set

    /**
     * 현재 raw zone 의 텍스트 범위 목록. transformOutput 시 갱신.
     * - isRawMode=true → 전체 텍스트 (모든 줄이 raw)
     * - isFocused=true → 커서 줄 1개
     * - 그 외 → 빈 리스트
     *
     * BlockDecorationDrawer 가 BLOCKQUOTE 좌측 바를 그릴 때 이 범위와 겹치는 줄은 skip.
     * (raw 마커가 보이는 상태에서 좌측 바도 함께 그리면 시각적 충돌)
     */
    var currentRawZones: List<IntRange> = emptyList()
        private set

    /**
     * BasicTextField의 포커스 여부. false이면 커서 줄 예외(raw zone)를 적용하지 않는다.
     */
    var isFocused: Boolean = false

    /**
     * dissolve 된 raw TextBlock(rawMode=true) 인지 여부.
     * true 면 전체 텍스트가 raw zone 으로 처리되어 인라인 서식이 모든 줄에서 비활성.
     * (docs/markdown-editor.md의 dissolve 정책)
     */
    var isRawMode: Boolean = false

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

        // raw zone: rawMode=true → 전체, focus → 커서 줄, 그 외 → 빈 리스트
        val rawZones = when {
            isRawMode -> listOf(0 until text.length)
            !isFocused -> emptyList()
            else -> {
                val selMin = minOf(selection.start, selection.end)
                val selMax = maxOf(selection.start, selection.end)
                val cursorLineStart = if (selMin == 0) 0
                    else (text.lastIndexOf('\n', selMin - 1) + 1)
                val cursorLineEnd = text.indexOf('\n', selMax).let {
                    if (it == -1) text.length else it
                }
                listOf(cursorLineStart until cursorLineEnd)
            }
        }
        // 외부 노출 (BlockDecorationDrawer 가 BLOCKQUOTE 좌측 바 클리핑에 사용)
        currentRawZones = rawZones

        // 일반 focus 상태에서는 활성 줄의 Markdown 문법 마커만 raw로 남기고,
        // bold/italic/code 같은 내용 스타일은 즉시 적용한다. dissolve rawMode만 완전 raw다.
        val codeRanges = mutableListOf<IntRange>()
        for ((range, style) in cachedSpans) {
            when (markdownSpanApplication(style, config, isFocused, isRawMode)) {
                MarkdownSpanApplication.Everywhere -> applySpan(range, style)
                MarkdownSpanApplication.OutsideRawZones -> {
                    applySpanOutsideRawZones(range, style, rawZones)
                }
            }
            if (style === config.codeInline && !isRawMode) codeRanges += range
        }
        inlineCodeRanges = codeRanges
    }

    private fun TextFieldBuffer.applySpan(range: IntRange, style: SpanStyle) {
        val start = range.first.coerceIn(0, length)
        val end = (range.last + 1).coerceIn(start, length)
        if (start < end) addStyle(style, start, end)
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

internal enum class MarkdownSpanApplication {
    Everywhere,
    OutsideRawZones,
}

/**
 * OutputTransformation의 interaction 상태별 span 적용 정책.
 *
 * - unfocused: marker와 content를 모두 preview
 * - focused: 활성 줄에서는 marker/prefix만 raw, content style은 유지
 * - rawMode: 전체 raw zone 바깥에만 적용(실제로는 전체가 raw이므로 모든 style 비활성)
 */
internal fun markdownSpanApplication(
    style: SpanStyle,
    config: MarkdownStyleConfig,
    isFocused: Boolean,
    isRawMode: Boolean,
): MarkdownSpanApplication {
    if (isRawMode) return MarkdownSpanApplication.OutsideRawZones
    if (!isFocused) return MarkdownSpanApplication.Everywhere

    val isSyntaxStyle = style === config.marker ||
        style === config.blockTransparent ||
        style === config.bulletPrefix ||
        style === config.orderedPrefix ||
        style === config.calloutIndicator
    return if (isSyntaxStyle) {
        MarkdownSpanApplication.OutsideRawZones
    } else {
        MarkdownSpanApplication.Everywhere
    }
}
