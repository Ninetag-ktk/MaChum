package com.ninetag.machum.markdown.parser

import com.ninetag.machum.markdown.token.InlineToken

class InlineParser {

    fun parse(text: String): List<InlineToken> {
        return parseRange(text, 0, text.length)
    }

    private fun parseRange(text: String, from: Int, to: Int): List<InlineToken> {
        val result = mutableListOf<InlineToken>()
        var i = from
        val buffer = StringBuilder()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                result.add(InlineToken.Text(buffer.toString()))
                buffer.clear()
            }
        }

        while (i < to) {
            val ch = text[i]

            // 줄바꿈
            if (ch == '\n') {
                flushBuffer()
                result.add(InlineToken.LineBreak)
                i++
                continue
            }

            // InlineCode
            if (ch == '`') {
                val end = text.indexOf('`', i + 1)
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    result.add(InlineToken.InlineCode(text.substring(i + 1, end)))
                    i = end + 1
                }
                continue
            }

            // ExternalLink — [text](url)
            if (ch == '[' && !text.startsWith("[[", i)) {
                val closeBracket = text.indexOf(']', i + 1)
                val openParen = if (closeBracket != -1) text.indexOf('(', closeBracket) else -1
                val closeParen = if (openParen == closeBracket + 1) text.indexOf(')', openParen + 1) else -1
                if (closeBracket != -1 && openParen == closeBracket + 1 && closeParen != -1) {
                    flushBuffer()
                    val linkText = text.substring(i + 1, closeBracket)
                    val url = text.substring(openParen + 1, closeParen)
                    result.add(InlineToken.ExternalLink(text = linkText, url = url))
                    i = closeParen + 1
                } else {
                    buffer.append(ch)
                    i++
                }
                continue
            }

            // EmbedLink 인라인 — ![[파일명]]
            if (text.startsWith("![[", i)) {
                val end = text.indexOf("]]", i + 3)
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    result.add(InlineToken.EmbedLink(fileName = text.substring(i + 3, end)))
                    i = end + 2
                }
                continue
            }

            // WikiLink — [[target]] 또는 [[target|alias]]
            if (text.startsWith("[[", i)) {
                val end = text.indexOf("]]", i + 2)
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    val inner = text.substring(i + 2, end)
                    val pipeIndex = inner.indexOf('|')
                    if (pipeIndex == -1) {
                        result.add(InlineToken.WikiLink(target = inner))
                    } else {
                        result.add(InlineToken.WikiLink(
                            target = inner.substring(0, pipeIndex),
                            alias = inner.substring(pipeIndex + 1)
                        ))
                    }
                    i = end + 2
                }
                continue
            }

            // Bold+Italic — *** 또는 ___
            if (text.startsWith("***", i) || text.startsWith("___", i)) {
                val delim = text.substring(i, i + 3)
                val end = findDelimiter(text, delim, i + 3, to, isUnderscore = delim == "___")
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    val inner = parseRange(text, i + 3, end)
                    result.add(InlineToken.Bold(listOf(InlineToken.Italic(inner))))
                    i = end + 3
                }
                continue
            }

            // Bold — ** 또는 __
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                val delim = text.substring(i, i + 2)
                val end = findDelimiter(text, delim, i + 2, to, isUnderscore = delim == "__")
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    val inner = parseRange(text, i + 2, end)
                    result.add(InlineToken.Bold(inner))
                    i = end + 2
                }
                continue
            }

            // Strikethrough — ~~
            if (text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    val inner = parseRange(text, i + 2, end)
                    result.add(InlineToken.Strikethrough(inner))
                    i = end + 2
                }
                continue
            }

            // Highlight — ==
            if (text.startsWith("==", i)) {
                val end = text.indexOf("==", i + 2)
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    val inner = parseRange(text, i + 2, end)
                    result.add(InlineToken.Highlight(inner))
                    i = end + 2
                }
                continue
            }

            // Italic — * 또는 _
            if (ch == '*' || ch == '_') {
                val isUnderscore = ch == '_'

                // _ 앞 문자가 일반 문자면 서식으로 인식하지 않음
                if (isUnderscore && i > from && text[i - 1].isLetterOrDigit()) {
                    buffer.append(ch)
                    i++
                    continue
                }

                val end = findDelimiter(text, ch.toString(), i + 1, to, isUnderscore)
                if (end == -1) {
                    buffer.append(ch)
                    i++
                } else {
                    flushBuffer()
                    val inner = parseRange(text, i + 1, end)
                    result.add(InlineToken.Italic(inner))
                    i = end + 1
                }
                continue
            }

            buffer.append(ch)
            i++
        }

        flushBuffer()
        return result
    }

    /**
     * 닫기 구분자 위치를 탐색
     * isUnderscore == true 인 경우 닫기 구분자 뒤 문자가 일반 문자면 무효 처리
     */
    private fun findDelimiter(text: String, delim: String, from: Int, to: Int, isUnderscore: Boolean): Int {
        var i = from
        while (i <= to - delim.length) {
            if (text.startsWith(delim, i)) {
                if (isUnderscore) {
                    val afterIndex = i + delim.length
                    val afterChar = text.getOrNull(afterIndex)
                    // 닫기 구분자 뒤가 일반 문자면 무효
                    if (afterChar != null && afterChar.isLetterOrDigit()) {
                        i++
                        continue
                    }
                }
                return i
            }
            i++
        }
        return -1
    }
}