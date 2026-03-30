package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle

/**
 * Phase 2 OutputTransformation.
 *
 * 비활성 줄: 기호에 MARKER(투명 + 극소 크기) 적용, 내용에 서식 SpanStyle 적용.
 * 활성 줄(커서 위치): 변환 없음 → raw 텍스트 그대로 표시.
 *
 * 커서가 멀티라인 블록(CodeBlock, Callout) 내부에 있으면 전체 블록을 raw 표시.
 */
internal class RawMarkdownOutputTransformation(
    private val config: MarkdownStyleConfig = MarkdownStyleConfig(),
) : OutputTransformation {

    private var cachedText: String = ""
    private var cachedSpans: List<Pair<IntRange, SpanStyle>> = emptyList()
    private var cachedBlocks: List<BlockRange> = emptyList()

    /** DrawBehind에서 블록 데코레이션 그리기에 사용 */
    val blockRanges: List<BlockRange> get() = cachedBlocks

    /** 현재 활성(raw) 블록 범위. DrawBehind에서 활성 블록 장식 스킵에 사용 */
    var activeBlockRange: IntRange? = null
        private set

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

        // 커서가 위치한 줄의 경계 계산
        val cursorPos = selection.start
        val cursorLineStart = if (cursorPos == 0) 0
        else (text.lastIndexOf('\n', cursorPos - 1) + 1)
        val cursorLineEnd = text.indexOf('\n', cursorPos).let {
            if (it == -1) text.length else it
        }

        // 블록 수준 커서 감지: 커서가 멀티라인 블록 내부이면 전체 블록을 raw 표시
        val activeBlock = cachedBlocks.firstOrNull { cursorPos in it.textRange }
        val rawZoneStart: Int
        val rawZoneEnd: Int
        if (activeBlock != null) {
            rawZoneStart = activeBlock.textRange.first
            rawZoneEnd = activeBlock.textRange.last + 1 // exclusive
            activeBlockRange = activeBlock.textRange
        } else {
            rawZoneStart = cursorLineStart
            rawZoneEnd = cursorLineEnd
            activeBlockRange = null
        }

        // 비활성 줄의 스팬 적용. 멀티라인 스팬이 raw zone과 겹치면 클리핑하여
        // raw zone 부분만 제외하고 나머지에는 서식을 유지한다.
        for ((range, style) in cachedSpans) {
            val spanStart = range.first
            val spanEnd = range.last // inclusive

            if (spanStart >= rawZoneEnd || spanEnd < rawZoneStart) {
                // raw zone과 겹치지 않음 → 전체 적용
                val start = spanStart.coerceIn(0, length)
                val end = (spanEnd + 1).coerceIn(start, length)
                if (start < end) addStyle(style, start, end)
            } else {
                // raw zone과 겹침 → 클리핑: raw zone 전후 부분만 적용
                if (spanStart < rawZoneStart) {
                    val start = spanStart.coerceIn(0, length)
                    val end = rawZoneStart.coerceIn(start, length)
                    if (start < end) addStyle(style, start, end)
                }
                if (spanEnd >= rawZoneEnd) {
                    val nextStart = if (rawZoneEnd < text.length) rawZoneEnd + 1 else rawZoneEnd
                    val start = nextStart.coerceIn(0, length)
                    val end = (spanEnd + 1).coerceIn(start, length)
                    if (start < end) addStyle(style, start, end)
                }
            }
        }
    }
}
