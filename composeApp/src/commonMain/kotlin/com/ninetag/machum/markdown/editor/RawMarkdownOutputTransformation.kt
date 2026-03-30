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
 * 커서가 이동할 때마다 selection 이 바뀌고 → transformOutput() 재실행 → 즉각 반응.
 */
internal class RawMarkdownOutputTransformation(
    private val config: MarkdownStyleConfig = MarkdownStyleConfig(),
) : OutputTransformation {

    private var cachedText: String = ""
    private var cachedSpans: List<Pair<IntRange, SpanStyle>> = emptyList()

    override fun TextFieldBuffer.transformOutput() {
        val text = toString()
        if (text.isEmpty()) return

        // 텍스트가 변경된 경우에만 재스캔
        if (text != cachedText) {
            cachedText = text
            cachedSpans = MarkdownPatternScanner.scan(text, config)
        }

        // 커서가 위치한 줄의 경계 계산
        val cursorPos = selection.start
        val cursorLineStart = if (cursorPos == 0) 0
        else (text.lastIndexOf('\n', cursorPos - 1) + 1)
        val cursorLineEnd = text.indexOf('\n', cursorPos).let {
            if (it == -1) text.length else it
        }

        // 비활성 줄의 스팬 적용. 멀티라인 스팬이 커서 줄과 겹치면 클리핑하여
        // 커서 줄 부분만 제외하고 나머지에는 서식을 유지한다.
        for ((range, style) in cachedSpans) {
            val spanStart = range.first
            val spanEnd = range.last // inclusive

            if (spanStart >= cursorLineEnd || spanEnd < cursorLineStart) {
                // 커서 줄과 겹치지 않음 → 전체 적용
                val start = spanStart.coerceIn(0, length)
                val end = (spanEnd + 1).coerceIn(start, length)
                if (start < end) addStyle(style, start, end)
            } else {
                // 커서 줄과 겹침 → 클리핑: 커서 줄 전후 부분만 적용
                if (spanStart < cursorLineStart) {
                    val start = spanStart.coerceIn(0, length)
                    val end = cursorLineStart.coerceIn(start, length)
                    if (start < end) addStyle(style, start, end)
                }
                if (spanEnd >= cursorLineEnd) {
                    val nextLineStart = if (cursorLineEnd < text.length) cursorLineEnd + 1 else cursorLineEnd
                    val start = nextLineStart.coerceIn(0, length)
                    val end = (spanEnd + 1).coerceIn(start, length)
                    if (start < end) addStyle(style, start, end)
                }
            }
        }
    }
}
