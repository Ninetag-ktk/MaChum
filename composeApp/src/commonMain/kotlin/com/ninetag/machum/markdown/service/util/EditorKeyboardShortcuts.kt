package com.ninetag.machum.markdown.service.util

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * 하드웨어 키보드 단축키 핸들러.
 *
 * `onPreviewKeyEvent` 에서 호출하여 텍스트 필드의 기본 키 처리 전에 단축키를 가로챈다.
 *
 * | 단축키 | 동작 |
 * |---|---|
 * | Ctrl/Cmd + B | Bold `**` 토글 |
 * | Ctrl/Cmd + I | Italic `*` 토글 |
 * | Ctrl/Cmd + E | InlineCode `` ` `` 토글 |
 * | Ctrl/Cmd + Shift + S | Strikethrough `~~` 토글 |
 * | Ctrl/Cmd + Shift + X | Strikethrough `~~` 토글 |
 * | Ctrl/Cmd + Shift + H | Highlight `==` 토글 |
 *
 * @return `true` 단축키가 처리됨 (이벤트 소비), `false` 해당 없음
 */
internal fun handleEditorKeyEvent(
    event: KeyEvent,
    textFieldState: TextFieldState,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    val isPrimaryModifier = event.isCtrlPressed || event.isMetaPressed
    if (!isPrimaryModifier) return false

    val isShift = event.isShiftPressed

    return when {
        !isShift && event.key == Key.B -> {
            RawStyleToggle.toggleInlineStyle(textFieldState, "**")
            true
        }
        !isShift && event.key == Key.I -> {
            RawStyleToggle.toggleInlineStyle(textFieldState, "*")
            true
        }
        !isShift && event.key == Key.E -> {
            RawStyleToggle.toggleInlineStyle(textFieldState, "`")
            true
        }
        isShift && (event.key == Key.S || event.key == Key.X) -> {
            RawStyleToggle.toggleInlineStyle(textFieldState, "~~")
            true
        }
        isShift && event.key == Key.H -> {
            RawStyleToggle.toggleInlineStyle(textFieldState, "==")
            true
        }
        else -> false
    }
}
