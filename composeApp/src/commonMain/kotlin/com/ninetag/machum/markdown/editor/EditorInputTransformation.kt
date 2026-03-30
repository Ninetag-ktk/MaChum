package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

@OptIn(ExperimentalFoundationApi::class)
class EditorInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return

        // changes API 호출 실패 시 (IME 조합 특수 상태 등) 안전하게 종료
        val change = try { changes.getRange(0) } catch (_: Exception) { return }
        if (change.end - change.start != 1) return
        if (change.start < 0 || change.end > length) return

        val insertedChar = toString().getOrNull(change.start) ?: return

        // auto-close 트리거 문자가 아니면 종료
        // 한글·CJK 등 IME 조합 문자는 트리거에 포함되지 않으므로 자연스럽게 통과
        if (insertedChar !in TRIGGER_CHARS) return

        // 기존 텍스트를 대체하는 변경인 경우 auto-close 하지 않음
        // (선택 영역 대체, IME 조합 업데이트 등)
        // getOriginalRange가 NPE를 던지는 경우 replacement로 간주하여 종료
        val isReplacement = try {
            !changes.getOriginalRange(0).collapsed
        } catch (_: Exception) {
            true
        }
        if (isReplacement) return

        if (!selection.collapsed) return

        val text = toString()
        // change.end = 타이핑된 문자 바로 다음 위치 (커서가 있어야 할 자리)
        val before = text.substring(0, change.end)
        val afterChar = text.getOrNull(change.end)?.toString()

        // Tab → 2 spaces
        if (insertedChar == '\t') {
            replace(change.start, change.end, "  ")
            selection = TextRange(change.start + 2)
            return
        }

        // Auto-close: 긴 패턴 먼저 체크
        val closer = when {
            before.endsWith("![[")                                               -> "]]"
            before.endsWith("[[")                                                -> "]]"
            before.endsWith("**") && afterChar != "*"                            -> "**"
            before.endsWith("~~") && afterChar != "~"                            -> "~~"
            before.endsWith("==") && afterChar != "="                            -> "=="
            before.endsWith("`") && before.dropLast(1).lastOrNull() != '`'
                    && afterChar != "`"                                          -> "`"
            before.endsWith("*") && before.dropLast(1).lastOrNull() != '*'
                    && afterChar != "*"                                          -> "*"
            else                                                                 -> return
        }

        // closer를 타이핑된 문자 바로 뒤에 삽입하고 커서를 그 사이에 배치
        // selection.start 대신 change.end를 직접 사용 — 플랫폼별 selection 불일치 회피
        replace(change.end, change.end, closer)
        selection = TextRange(change.end)
    }

    companion object {
        private const val TRIGGER_CHARS = "*~=`[\t"
    }
}
