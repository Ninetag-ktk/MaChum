package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/**
 * 마크다운 에디터의 핵심 상태.
 *
 * - [textFieldState]: BasicTextField 에 연결되는 Compose 텍스트 상태 (항상 clean text 유지).
 * - [spans]: 현재 clean text 에 적용된 서식 범위 목록. `mutableStateOf` 로 관리되어
 *   변경 시 자동으로 recomposition 이 발생한다.
 *
 * 파일 전환은 호출부에서 `key(file.name) { }` 로 처리 → 새 인스턴스 생성.
 */
class MarkdownEditorState(initialMarkdown: String) {

    val textFieldState = TextFieldState()

    private var _spans by mutableStateOf<List<MarkupStyleRange>>(emptyList())
    val spans: List<MarkupStyleRange> get() = _spans

    init {
        setMarkdown(initialMarkdown)
    }

    /**
     * InputTransformation 에서 호출된다.
     * [buffer] 는 사용자 입력이 반영된 제안 상태이며, 이 함수 안에서 수정할 수 있다.
     *
     * 처리 흐름:
     * 1. 기존 스팬을 텍스트 변경에 맞게 shift
     * 2. MarkdownStyleProcessor 로 패턴 감지
     * 3. 패턴이 완성됐으면 buffer 를 clean text 로 교체 + 스팬 갱신
     *    패턴이 없으면 shift 된 스팬만 반영
     */
    internal fun applyInput(buffer: TextFieldBuffer) {
        val prevLength   = textFieldState.text.length
        val newText      = buffer.toString()
        val cursorPos    = buffer.selection.start
        val lengthDelta  = newText.length - prevLength

        val changeOrigin = SpanManager.resolveChangeOrigin(cursorPos, lengthDelta, prevLength)
        val shifted      = SpanManager.shiftSpans(_spans, changeOrigin, lengthDelta)

        val result = MarkdownStyleProcessor.process(newText, cursorPos)
        val merged = if (result != null) {
            applyMinimalEdits(buffer, result.cleanText, result.cursorPosition)
            SpanManager.mergeSpans(shifted, result.spans)
        } else {
            shifted
        }

        // Heading 스팬은 항상 현재 줄 전체를 커버하도록 재조정.
        // shiftSpans 로 스팬 끝이 줄 경계를 벗어나거나 짧아질 수 있으므로 필요.
        val finalText = buffer.toString()
        _spans = reanchorHeadingSpans(merged, finalText)
    }

    /**
     * Heading 스팬을 현재 텍스트의 실제 줄 경계(lineStart..lineEnd)에 맞게 재조정한다.
     * 해당 줄이 비어 있으면 스팬을 제거한다.
     */
    private fun reanchorHeadingSpans(
        spans: List<MarkupStyleRange>,
        text: String,
    ): List<MarkupStyleRange> = spans.mapNotNull { span ->
        if (!span.style.isHeading) return@mapNotNull span

        val prevNewline = text.lastIndexOf('\n', (span.start - 1).coerceAtLeast(0))
        val lineStart   = if (prevNewline == -1) 0 else prevNewline + 1
        val nextNewline = text.indexOf('\n', lineStart)
        val lineEnd     = if (nextNewline == -1) text.length else nextNewline

        if (lineStart >= lineEnd) null
        else span.copy(start = lineStart, end = lineEnd)
    }

    /**
     * IME 조합 상태를 보존하기 위해 buffer 를 최소 편집으로 [targetText] 로 맞춘다.
     *
     * 삭제만 발생하고 해당 삭제가 커서 앞에서 일어나는 경우(순수 마크다운 기호 제거)만
     * 부분 replace 를 사용한다. 그 외에는 전체 replace 로 fallback 한다.
     */
    private fun applyMinimalEdits(
        buffer: TextFieldBuffer,
        targetText: String,
        targetCursor: Int,
    ) {
        val currentText = buffer.toString()
        if (currentText == targetText) {
            buffer.selection = TextRange(targetCursor)
            return
        }

        // 공통 접두사 길이
        var prefixLen = 0
        while (prefixLen < currentText.length && prefixLen < targetText.length
            && currentText[prefixLen] == targetText[prefixLen]
        ) prefixLen++

        // 공통 접미사 길이
        var suffixLen = 0
        val maxSuffix = minOf(currentText.length - prefixLen, targetText.length - prefixLen)
        while (suffixLen < maxSuffix
            && currentText[currentText.length - 1 - suffixLen] == targetText[targetText.length - 1 - suffixLen]
        ) suffixLen++

        val deleteStart = prefixLen
        val deleteEnd   = currentText.length - suffixLen
        val insertText  = targetText.substring(prefixLen, targetText.length - suffixLen)

        // 순수 삭제이고, 삭제 구간이 커서 앞에 있을 때만 부분 편집
        if (insertText.isEmpty() && deleteEnd <= buffer.selection.start) {
            buffer.replace(deleteStart, deleteEnd, "")
        } else {
            buffer.replace(0, buffer.length, targetText)
        }
        buffer.selection = TextRange(targetCursor)
    }

    /**
     * 저장용 마크다운 직렬화. clean text + spans → 마크다운 문자열.
     */
    fun toMarkdown(): String =
        MarkdownSerializer.toMarkdown(textFieldState.text.toString(), _spans)

    /**
     * 파일 내용으로 상태를 초기화한다.
     * init{} 및 외부 파일 교체 시 호출.
     */
    fun setMarkdown(markdown: String) {
        val result    = MarkdownStyleProcessor.process(markdown, markdown.length)
        val cleanText = result?.cleanText ?: markdown
        textFieldState.edit {
            replace(0, length, cleanText)
            selection = TextRange(length)
        }
        _spans = result?.spans ?: emptyList()
    }
}
