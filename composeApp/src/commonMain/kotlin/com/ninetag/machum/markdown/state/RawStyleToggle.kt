package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/**
 * Raw text 서식 토글 유틸리티.
 *
 * [TextFieldState] 에서 마크다운 마커를 직접 삽입/제거한다.
 * 키보드 단축키와 향후 툴바 버튼이 공용으로 사용.
 */
internal object RawStyleToggle {

    /**
     * 인라인 마커(`**`, `*`, `` ` ``, `~~`, `==`)를 토글한다.
     *
     * - 선택 영역 있음: 마커로 감싸져 있으면 제거, 아니면 추가
     * - 선택 영역 없음: `marker + marker` 삽입 후 커서를 사이에 배치
     */
    fun toggleInlineStyle(state: TextFieldState, marker: String) {
        state.edit {
            val sel = selection
            if (sel.collapsed) {
                // 커서만 있을 때: marker + marker 삽입, 커서를 사이에 배치
                val pos = sel.start
                replace(pos, pos, marker + marker)
                selection = TextRange(pos + marker.length)
            } else {
                val start = sel.min
                val end = sel.max
                val text = toString()
                val markerLen = marker.length

                // 선택 영역이 이미 마커로 감싸져 있는지 확인
                val wrappedOutside = start >= markerLen && end + markerLen <= text.length
                        && text.substring(start - markerLen, start) == marker
                        && text.substring(end, end + markerLen) == marker

                val selectedText = text.substring(start, end)
                val wrappedInside = selectedText.length >= markerLen * 2
                        && selectedText.startsWith(marker)
                        && selectedText.endsWith(marker)

                when {
                    wrappedOutside -> {
                        // 바깥쪽 마커 제거
                        replace(end, end + markerLen, "")
                        replace(start - markerLen, start, "")
                        selection = TextRange(start - markerLen, end - markerLen)
                    }
                    wrappedInside -> {
                        // 안쪽 마커 제거
                        val inner = selectedText.substring(markerLen, selectedText.length - markerLen)
                        replace(start, end, inner)
                        selection = TextRange(start, start + inner.length)
                    }
                    else -> {
                        // 마커로 감싸기
                        replace(end, end, marker)
                        replace(start, start, marker)
                        selection = TextRange(start + markerLen, end + markerLen)
                    }
                }
            }
        }
    }

    /**
     * 현재 줄의 heading level 을 토글한다.
     *
     * - 같은 레벨이면 제거
     * - 다른 레벨이면 교체
     * - 없으면 추가
     */
    fun toggleHeading(state: TextFieldState, level: Int) {
        state.edit {
            val text = toString()
            val cursorPos = selection.start
            val lineStart = text.lastIndexOf('\n', cursorPos - 1) + 1
            val lineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
            val lineText = text.substring(lineStart, lineEnd)

            val headingMatch = HEADING_REGEX.matchAt(lineText, 0)
            val targetPrefix = "#".repeat(level) + " "

            when {
                headingMatch != null -> {
                    val existingPrefix = headingMatch.value
                    val content = lineText.drop(existingPrefix.length)
                    if (existingPrefix == targetPrefix) {
                        // 같은 레벨 → 제거
                        replace(lineStart, lineEnd, content)
                        val newCursor = (cursorPos - existingPrefix.length).coerceIn(lineStart, lineStart + content.length)
                        selection = TextRange(newCursor)
                    } else {
                        // 다른 레벨 → 교체
                        replace(lineStart, lineEnd, targetPrefix + content)
                        val delta = targetPrefix.length - existingPrefix.length
                        val newCursor = (cursorPos + delta).coerceIn(lineStart, lineStart + targetPrefix.length + content.length)
                        selection = TextRange(newCursor)
                    }
                }
                else -> {
                    // 없음 → 추가
                    replace(lineStart, lineStart, targetPrefix)
                    selection = TextRange(cursorPos + targetPrefix.length)
                }
            }
        }
    }

    /**
     * 현재 줄의 블록 prefix(`- `, `* `, `> `, `1. `, `- [ ] `)를 토글한다.
     *
     * - 같은 prefix 면 제거
     * - 다른 prefix 면 교체
     * - 없으면 추가
     */
    fun toggleBlockPrefix(state: TextFieldState, prefix: String) {
        state.edit {
            val text = toString()
            val cursorPos = selection.start
            val lineStart = text.lastIndexOf('\n', cursorPos - 1) + 1
            val lineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
            val lineText = text.substring(lineStart, lineEnd)

            // 들여쓰기 보존
            val indent = lineText.takeWhile { it == ' ' || it == '\t' }
            val rest = lineText.drop(indent.length)

            val existingPrefix = detectExistingBlockPrefix(rest)

            when {
                existingPrefix != null && existingPrefix == prefix -> {
                    // 같은 prefix → 제거
                    val content = rest.drop(existingPrefix.length)
                    replace(lineStart, lineEnd, indent + content)
                    val newCursor = (cursorPos - existingPrefix.length).coerceIn(lineStart, lineStart + indent.length + content.length)
                    selection = TextRange(newCursor)
                }
                existingPrefix != null -> {
                    // 다른 prefix → 교체
                    val content = rest.drop(existingPrefix.length)
                    replace(lineStart, lineEnd, indent + prefix + content)
                    val delta = prefix.length - existingPrefix.length
                    val newCursor = (cursorPos + delta).coerceIn(lineStart, lineStart + indent.length + prefix.length + content.length)
                    selection = TextRange(newCursor)
                }
                else -> {
                    // 없음 → 추가
                    replace(lineStart + indent.length, lineStart + indent.length, prefix)
                    selection = TextRange(cursorPos + prefix.length)
                }
            }
        }
    }

    private fun detectExistingBlockPrefix(rest: String): String? {
        BLOCK_PREFIX_REGEX.matchAt(rest, 0)?.let { return it.value }
        return null
    }

    private val HEADING_REGEX = Regex("""#{1,6} """)
    private val BLOCK_PREFIX_REGEX = Regex("""(?:- \[[xX ]] |[-*] |\d+\. |> )""")
}
