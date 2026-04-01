package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import com.ninetag.machum.markdown.state.InlineStyleScanner
import com.ninetag.machum.markdown.service.MarkdownStyleConfig

/**
 * 인라인 서식만 적용하는 경량 OutputTransformation.
 *
 * 블록 수준 처리(헤딩, 코드블록, 블록 투명) 없이
 * `**볼드**`, `*이탤릭*`, `~~취소선~~`, `==하이라이트==`, `` `코드` ``,
 * `[[위키링크]]`, `[텍스트](URL)` 등 인라인 마커만 처리한다.
 *
 * [isFocused]가 false이면 모든 줄에 서식을 적용한다 (커서 줄 예외 없음).
 * [isFocused]가 true이면 커서 줄만 raw 표시하고 나머지에 서식 적용.
 *
 * Callout/Table 오버레이 내부의 TextField에서 사용.
 */
internal class InlineOnlyOutputTransformation(
    private val config: MarkdownStyleConfig = MarkdownStyleConfig(),
) : OutputTransformation {

    /** 오버레이 Composable에서 포커스 상태에 따라 설정 */
    var isFocused: Boolean = false

    private var cachedText: String = ""
    private var cachedSpans: List<Pair<IntRange, androidx.compose.ui.text.SpanStyle>> = emptyList()

    override fun TextFieldBuffer.transformOutput() {
        val text = toString()
        if (text.isEmpty()) return

        if (text != cachedText) {
            cachedText = text
            // Full scanner로 callout 헤더/blockquote/인라인 서식 모두 처리
            cachedSpans = MarkdownPatternScanner.scan(text, config).spans
        }

        if (!isFocused) {
            // 포커스 없음 → 모든 줄에 서식 적용
            for ((range, style) in cachedSpans) {
                val start = range.first.coerceIn(0, length)
                val end = (range.last + 1).coerceIn(start, length)
                if (start < end) addStyle(style, start, end)
            }
            return
        }

        // 포커스 있음 → 커서 줄만 raw
        val cursorPos = selection.start
        val cursorLineStart = if (cursorPos == 0) 0
        else (text.lastIndexOf('\n', cursorPos - 1) + 1)
        val cursorLineEnd = text.indexOf('\n', cursorPos).let {
            if (it == -1) text.length else it
        }

        for ((range, style) in cachedSpans) {
            val spanStart = range.first
            val spanEnd = range.last

            if (spanStart >= cursorLineEnd || spanEnd < cursorLineStart) {
                val start = spanStart.coerceIn(0, length)
                val end = (spanEnd + 1).coerceIn(start, length)
                if (start < end) addStyle(style, start, end)
            }
        }
    }
}
