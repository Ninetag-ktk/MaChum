package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

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

        // Smart Enter: 블록 prefix 자동 continuation
        if (insertedChar == '\n') {
            handleSmartEnter(change.start)
            return
        }

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
        // 이미 서식 내부에 있으면 닫는 마커이므로 auto-close하지 않음
        val closer = when {
            before.endsWith("![[")                                               -> "]]"
            before.endsWith("[[")                                                -> "]]"
            before.endsWith("**") && afterChar != "*"
                    && !isInsideMarker(before.dropLast(2), "**")                 -> "**"
            before.endsWith("~~") && afterChar != "~"
                    && !isInsideMarker(before.dropLast(2), "~~")                 -> "~~"
            before.endsWith("==") && afterChar != "="
                    && !isInsideMarker(before.dropLast(2), "==")                 -> "=="
            before.endsWith("`") && before.dropLast(1).lastOrNull() != '`'
                    && afterChar != "`"
                    && !isInsideMarker(before.dropLast(1), "`")                  -> "`"
            before.endsWith("*") && before.dropLast(1).lastOrNull() != '*'
                    && afterChar != "*"
                    && !isInsideMarker(before.dropLast(1), "*")                  -> "*"
            else                                                                 -> return
        }

        // closer를 타이핑된 문자 바로 뒤에 삽입하고 커서를 그 사이에 배치
        // selection.start 대신 change.end를 직접 사용 — 플랫폼별 selection 불일치 회피
        replace(change.end, change.end, closer)
        selection = TextRange(change.end)
    }

    /**
     * Smart Enter: \n이 삽입된 위치(newlinePos) 이전 줄의 블록 prefix를 감지하여
     * 자동으로 continuation prefix를 삽입하거나, prefix-only 줄이면 prefix를 제거한다.
     */
    private fun TextFieldBuffer.handleSmartEnter(newlinePos: Int) {
        val text = toString()

        // newlinePos 이전 줄의 시작 위치
        val lineStart = text.lastIndexOf('\n', newlinePos - 1) + 1
        val lineText = text.substring(lineStart, newlinePos)

        val detected = detectBlockPrefix(lineText) ?: return

        val (indent, fullPrefix, continuation, contentAfterPrefix) = detected

        if (contentAfterPrefix.isEmpty()) {
            // prefix-only 줄 → prefix + \n 제거하여 빈 줄로 변환
            replace(lineStart, newlinePos + 1, "\n")
            selection = TextRange(lineStart + 1)
        } else {
            // 내용이 있는 줄 → \n 뒤에 continuation prefix 삽입
            val insert = indent + continuation
            replace(newlinePos + 1, newlinePos + 1, insert)
            selection = TextRange(newlinePos + 1 + insert.length)

            // Blockquote 외 블록 prefix: 첫 줄 위에 빈 줄이 없으면 삽입
            if (!continuation.trimStart().startsWith(">")) {
                ensureBlankLineBefore(lineStart)
            }
        }
    }

    private data class BlockPrefixResult(
        val indent: String,
        val fullPrefix: String,
        val continuation: String,
        val contentAfterPrefix: String,
    )

    /**
     * 줄 텍스트에서 블록 prefix를 감지한다.
     * checkbox > bullet > ordered > blockquote 순으로 체크.
     */
    private fun detectBlockPrefix(lineText: String): BlockPrefixResult? {
        // 들여쓰기 분리
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val rest = lineText.drop(indent.length)

        // checkbox: - [ ] 또는 - [x]
        CHECKBOX_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val content = rest.drop(prefix.length).trimStart()
            return BlockPrefixResult(indent, prefix, "- [ ] ", content)
        }

        // bullet: - 또는 *
        BULLET_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val content = rest.drop(prefix.length)
            return BlockPrefixResult(indent, prefix, prefix, content)
        }

        // ordered list: 숫자.
        ORDERED_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val num = match.groupValues[1].toInt()
            val content = rest.drop(prefix.length)
            return BlockPrefixResult(indent, prefix, "${num + 1}. ", content)
        }

        // blockquote: >
        BLOCKQUOTE_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val content = rest.drop(prefix.length)
            return BlockPrefixResult(indent, prefix, "> ", content)
        }

        return null
    }

    /**
     * 블록 시작 위치(lineStart) 위에 빈 줄이 없으면 삽입한다.
     * 문서 첫 줄이거나 이미 빈 줄이 있으면 아무것도 하지 않는다.
     */
    private fun TextFieldBuffer.ensureBlankLineBefore(lineStart: Int) {
        if (lineStart <= 0) return // 문서 첫 줄
        val text = toString()
        // lineStart - 1 은 이전 줄 끝의 \n
        val prevLineEnd = lineStart - 1
        val prevLineStart = text.lastIndexOf('\n', prevLineEnd - 1) + 1
        val prevLine = text.substring(prevLineStart, prevLineEnd)
        if (prevLine.isNotBlank()) {
            // 빈 줄이 아니므로 \n 삽입
            replace(lineStart, lineStart, "\n")
            // 커서 위치 1칸 보정
            selection = TextRange(selection.start + 1)
        }
    }

    /**
     * 텍스트에서 마커가 홀수 번 등장하면 현재 마커 내부에 있는 것으로 판단.
     * 같은 줄 내에서만 체크한다.
     */
    private fun isInsideMarker(textBefore: String, marker: String): Boolean {
        // 현재 줄만 확인
        val lineStart = textBefore.lastIndexOf('\n') + 1
        val line = textBefore.substring(lineStart)
        var count = 0
        var idx = 0
        while (idx <= line.length - marker.length) {
            if (line.substring(idx, idx + marker.length) == marker) {
                count++
                idx += marker.length
            } else {
                idx++
            }
        }
        return count % 2 == 1 // 홀수 = 열린 마커 안
    }

    companion object {
        private const val TRIGGER_CHARS = "*~=`[\t"

        private val CHECKBOX_REGEX = Regex("""- \[[xX ]] """)
        private val BULLET_REGEX = Regex("""[-*] """)
        private val ORDERED_REGEX = Regex("""(\d+)\. """)
        private val BLOCKQUOTE_REGEX = Regex("""> """)
    }
}
