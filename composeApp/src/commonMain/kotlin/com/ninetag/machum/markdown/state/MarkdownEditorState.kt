package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/**
 * 마크다운 에디터의 핵심 상태 (Phase 2 — raw text 방식).
 *
 * [textFieldState] 에 raw 마크다운 텍스트를 그대로 저장한다.
 * 서식은 [RawMarkdownOutputTransformation] 이 실시간으로 계산한다.
 *
 * 파일 전환은 호출부에서 `key(file.name) { }` 로 처리 → 새 인스턴스 생성.
 */
class MarkdownEditorState(initialMarkdown: String) {

    val textFieldState = TextFieldState()

    init {
        setMarkdown(initialMarkdown)
    }

    /**
     * 파일 내용으로 상태를 초기화한다.
     * raw 마크다운 텍스트를 그대로 주입한다.
     */
    fun setMarkdown(markdown: String) {
        textFieldState.edit {
            replace(0, length, markdown)
            selection = TextRange(length)
        }
    }
}
