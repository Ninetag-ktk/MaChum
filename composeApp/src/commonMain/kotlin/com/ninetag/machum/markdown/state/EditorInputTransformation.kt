package com.ninetag.machum.markdown.state

import com.ninetag.machum.external.WorkspaceLinkScanner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

@OptIn(ExperimentalFoundationApi::class)
class EditorInputTransformation(
    private val textFieldState: TextFieldState? = null,
    private val onWikiLinkCreated: ((String, IntRange) -> Unit)? = null,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return

        // changes API 호출 실패 시 (IME 조합 특수 상태 등) 안전하게 종료
        val change = try { changes.getRange(0) } catch (_: Exception) { return }
        val originalRange = try { changes.getOriginalRange(0) } catch (_: Exception) { return }

        // 목록 행 삭제는 명시적인 구조 변경이다. 삭제 뒤 같은 연속 목록 블록의 ordered sibling만
        // 다시 계산하고, 문서 열기·붙여넣기나 일반 본문 삭제에는 적용하지 않는다.
        if (!originalRange.collapsed && change.collapsed) {
            orderedListDeletionEdit(
                originalText = originalText.toString(),
                updatedText = toString(),
                deletedOriginalRange = originalRange,
                selection = selection,
                updatedChangeStart = change.min,
            )?.let { edit ->
                replace(edit.range.first, edit.range.last + 1, edit.replacement)
                selection = edit.selection
            }
            return
        }

        if (change.end - change.start != 1) return
        if (change.start < 0 || change.end > length) return

        val insertedChar = toString().getOrNull(change.start) ?: return

        // Smart Enter: 블록 prefix 자동 continuation
        if (insertedChar == '\n') {
            var newlinePos = change.start
            // A newline at the end of a wiki token belongs after its auto-paired closers.
            // Leave selections, interior edits and live IME composition to the input system.
            val original = originalText.toString()
            if (textFieldState?.composition == null && originalRange.collapsed &&
                originalSelection.collapsed && selection.collapsed &&
                originalSelection.start == newlinePos && originalRange.start == newlinePos &&
                original.substring(newlinePos, minOf(newlinePos + 2, original.length)) == "]]" &&
                markdownInteractions(original).any { link ->
                    (link is MarkdownInteraction.InternalLink || link is MarkdownInteraction.Embed) &&
                        link.syntaxRange.last == newlinePos + 1 &&
                        '\r' !in original.substring(link.syntaxRange)
                } && !WorkspaceLinkScanner.excludedCode(original)[newlinePos]
            ) {
                replace(newlinePos, newlinePos + 3, "]]\n")
                newlinePos += 2
                selection = TextRange(newlinePos + 1)
            }
            handleSmartEnter(newlinePos)
            return
        }

        // Tab → 목록 깊이 저장 단위와 같은 4 spaces
        if (insertedChar == '\t') {
            replace(change.start, change.end, LIST_INDENT)
            selection = TextRange(change.start + LIST_INDENT.length)
            return
        }

        // auto-close 트리거 문자가 아니면 종료
        // 한글·CJK 등 IME 조합 문자는 트리거에 포함되지 않으므로 자연스럽게 통과
        if (insertedChar !in TRIGGER_CHARS) return

        markdownPairEdit(
            originalText = originalText.toString(),
            updatedText = toString(),
            originalRange = originalRange,
            changeRange = change,
            insertedChar = insertedChar,
        )?.let { edit ->
            replace(edit.range.first, edit.range.last + 1, edit.replacement)
            selection = edit.selection
            // Closing brackets make a new draft syntactically complete before typing its target.
            if (onWikiLinkCreated != null && insertedChar == '[' && originalRange.collapsed) {
                val text = toString()
                markdownLinkCompletionRequest(text, selection)
                    ?.takeIf { it.syntax == MarkdownLinkCompletionSyntax.WIKI }
                    ?.let { request ->
                        val close = text.indexOf("]]", request.replacementStart)
                        if (close >= 0) {
                            onWikiLinkCreated.invoke(text, request.replacementStart - 2 until close + 2)
                        }
                    }
            }
        }
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

        val (sourceIndent, continuation, contentAfterPrefix) = detected
        val indent = markdownListPresentations(text)
            .firstOrNull { it.range.first == lineStart }
            ?.let { LIST_INDENT.repeat(it.depth) }
            ?: sourceIndent

        if (contentAfterPrefix.isEmpty()) {
            // prefix-only 줄 → prefix + \n 제거하여 빈 줄로 변환
            replace(lineStart, newlinePos + 1, "\n")
            selection = TextRange(lineStart + 1)
        } else {
            // 내용이 있는 줄 → \n 뒤에 continuation prefix 삽입
            val insert = indent + continuation
            replace(newlinePos + 1, newlinePos + 1, insert)
            selection = TextRange(newlinePos + 1 + insert.length)
        }
    }

    private data class BlockPrefixResult(
        val indent: String,
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

        // checkbox: unordered 또는 ordered marker + [ ]/[x]
        CHECKBOX_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val content = rest.drop(prefix.length).trimStart()
            val marker = match.groupValues[1]
            val continuationMarker = marker.dropLast(1).toIntOrNull()?.let { "${it + 1}." } ?: marker
            return BlockPrefixResult(indent, "$continuationMarker [ ] ", content)
        }

        // bullet: - 또는 *
        BULLET_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val content = rest.drop(prefix.length)
            return BlockPrefixResult(indent, prefix, content)
        }

        // ordered list: 숫자.
        ORDERED_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val content = rest.drop(prefix.length)
            val currentNumber = match.groupValues[1]
            val nextNumber = currentNumber.toIntOrNull()?.let { it + 1 }?.toString() ?: currentNumber
            return BlockPrefixResult(indent, "$nextNumber. ", content)
        }

        // blockquote: > 또는 >> 또는 > > 등 (depth 유지, continuation은 항상 ">>" 형식)
        BLOCKQUOTE_REGEX.matchAt(rest, 0)?.let { match ->
            val prefix = match.value
            val content = rest.drop(prefix.length)
            val depth = prefix.count { it == '>' }
            val continuation = ">".repeat(depth) + " "
            return BlockPrefixResult(indent, continuation, content)
        }

        return null
    }

    companion object {
        private const val TRIGGER_CHARS = "*~=`[()]"

        private val CHECKBOX_REGEX = Regex("""([-*+]|\d+\.) \[[xX ]] """)
        private val BULLET_REGEX = Regex("""[-*+] """)
        private val ORDERED_REGEX = Regex("""(\d+)\. """)
        private val BLOCKQUOTE_REGEX = Regex("""(?:> ?)+""")
    }
}

internal data class MarkdownPairEdit(
    val range: IntRange,
    val replacement: String,
    val selection: TextRange,
)

/** 한 글자 입력을 대칭 서식 쌍으로 만들며, 세 번째 연속 backtick은 여는 code fence로 전환한다. */
internal fun markdownPairEdit(
    originalText: String,
    updatedText: String,
    originalRange: TextRange,
    changeRange: TextRange,
    insertedChar: Char,
): MarkdownPairEdit? {
    val changeStart = changeRange.min
    val changeEnd = changeRange.max

    if (insertedChar == ']' || insertedChar == ')') {
        if (originalRange.collapsed && originalText.getOrNull(originalRange.min) == insertedChar) {
            return MarkdownPairEdit(
                range = changeStart until changeEnd,
                replacement = "",
                selection = TextRange(changeStart + 1),
            )
        }
        return null
    }

    val closingChar = when (insertedChar) {
        '[' -> ']'
        '(' -> ')'
        '*', '~', '=', '`' -> insertedChar
        else -> return null
    }
    val maxDepth = when (insertedChar) {
        '*', '`' -> 3
        '~', '=' -> 2
        '[', '(' -> Int.MAX_VALUE
        else -> return null
    }

    if (!originalRange.collapsed) {
        val selectedStart = originalRange.min
        val selectedEnd = originalRange.max
        if (selectedStart !in 0..originalText.length || selectedEnd !in 0..originalText.length) return null
        val selectedText = originalText.substring(selectedStart, selectedEnd)
        val openingDepth = originalText.countBackward(selectedStart, insertedChar)
        val closingDepth = originalText.countForward(selectedEnd, closingChar)
        val existingDepth = minOf(openingDepth, closingDepth)
        val wrapsSelection = existingDepth < maxDepth && !(insertedChar == '`' && existingDepth == 2)
        val replacement = if (wrapsSelection) {
            "$insertedChar$selectedText$closingChar"
        } else {
            selectedText
        }
        val contentStart = changeStart + if (wrapsSelection) 1 else 0
        val contentEnd = contentStart + selectedText.length
        return MarkdownPairEdit(
            range = changeStart until changeEnd,
            replacement = replacement,
            selection = if (originalRange.start <= originalRange.end) {
                TextRange(contentStart, contentEnd)
            } else {
                TextRange(contentEnd, contentStart)
            },
        )
    }

    val openingDepth = updatedText.countBackward(changeEnd, insertedChar)
    val closingDepth = updatedText.countForward(changeEnd, closingChar)

    if (insertedChar == '`' && openingDepth == 3 && closingDepth == 2) {
        return MarkdownPairEdit(
            range = changeEnd until (changeEnd + closingDepth),
            replacement = "",
            selection = TextRange(changeEnd),
        )
    }

    if (openingDepth in 1..maxDepth && closingDepth == openingDepth - 1) {
        return MarkdownPairEdit(
            range = changeEnd until changeEnd,
            replacement = closingChar.toString(),
            selection = TextRange(changeEnd),
        )
    }

    if (openingDepth > maxDepth && closingDepth == openingDepth - 1) {
        return MarkdownPairEdit(
            range = changeStart until changeEnd,
            replacement = "",
            selection = TextRange(changeStart),
        )
    }

    // 자동 생성된 닫는 기호 바로 앞에서 같은 기호를 입력하면 중복하지 않고 건너뛴다.
    if (insertedChar == closingChar && openingDepth == 1 && closingDepth > 0) {
        return MarkdownPairEdit(
            range = changeStart until changeEnd,
            replacement = "",
            selection = TextRange(changeStart + closingDepth),
        )
    }
    return null
}

private fun String.countBackward(endExclusive: Int, char: Char): Int {
    var index = endExclusive - 1
    while (index >= 0 && this[index] == char) index--
    return endExclusive - index - 1
}

private fun String.countForward(start: Int, char: Char): Int {
    var index = start
    while (index < length && this[index] == char) index++
    return index - start
}

internal data class ListDepthEdit(
    val range: IntRange,
    val replacement: String,
    val selection: TextRange,
)

/** 현재 커서가 놓인 목록 행의 깊이만 한 단계 조정한다. */
internal fun listDepthEdit(
    text: String,
    selection: TextRange,
    outdent: Boolean,
): ListDepthEdit? {
    if (!selection.collapsed) return null
    val cursor = selection.start.coerceIn(0, text.length)
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
    val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val match = LIST_LINE_REGEX.find(line) ?: return null
    val indent = match.groupValues[1]
    val current = markdownListPresentations(text).firstOrNull { it.range.first == lineStart } ?: return null
    val depth = current.depth
    val targetDepth = if (outdent) depth - 1 else depth + 1
    if (targetDepth < 0) return null
    val replacement = LIST_INDENT.repeat(targetDepth)

    if (!current.isOrdered) {
        return ListDepthEdit(
            range = lineStart until (lineStart + indent.length),
            replacement = replacement,
            selection = TextRange((cursor + replacement.length - indent.length).coerceAtLeast(lineStart)),
        )
    }

    val indented = text.replaceRange(lineStart until (lineStart + indent.length), replacement)
    val renumbered = renumberOrderedListAfterDepthChange(
        text = indented,
        movedLineStart = lineStart,
    )
    val finalCurrent = markdownListPresentations(renumbered).firstOrNull { it.range.first == lineStart } ?: return null
    val sourceBodyStart = current.prefixRange.last + 1
    val finalBodyStart = finalCurrent.prefixRange.last + 1
    val finalCursor = if (cursor >= sourceBodyStart) {
        finalBodyStart + (cursor - sourceBodyStart)
    } else {
        lineStart + (cursor - lineStart).coerceAtMost(finalBodyStart - lineStart)
    }

    return singleListDepthEdit(text, renumbered, TextRange(finalCursor.coerceIn(0, renumbered.length)))
}

private fun renumberOrderedListAfterDepthChange(
    text: String,
    movedLineStart: Int,
): String {
    val lists = markdownListPresentations(text)
    val movedIndex = lists.indexOfFirst { it.range.first == movedLineStart }
    if (movedIndex < 0 || !lists[movedIndex].isOrdered) return text

    var blockStart = movedIndex
    while (blockStart > 0 && lists[blockStart].range.first == lists[blockStart - 1].range.last + 1) blockStart--
    var blockEnd = movedIndex + 1
    while (blockEnd < lists.size && lists[blockEnd].range.first == lists[blockEnd - 1].range.last + 1) blockEnd++

    val replacements = mutableMapOf<IntRange, String>()
    val nextNumberByDepth = mutableMapOf<Int, Int>()
    for (index in blockStart until blockEnd) {
        val list = lists[index]
        nextNumberByDepth.keys.removeAll { it > list.depth }
        if (!list.isOrdered) {
            nextNumberByDepth.remove(list.depth)
            continue
        }
        val numberRange = orderedNumberRange(text, list) ?: continue
        val sourceNumber = text.substring(numberRange).toIntOrNull() ?: continue
        val number = nextNumberByDepth[list.depth] ?: if (index == movedIndex) 1 else sourceNumber
        replacements[numberRange] = number.toString()
        nextNumberByDepth[list.depth] = number + 1
    }

    return StringBuilder(text).apply {
        replacements.entries.sortedByDescending { it.key.first }.forEach { (range, number) ->
            replace(range.first, range.last + 1, number)
        }
    }.toString()
}

/**
 * 사용자가 ordered 목록 행을 삭제한 경우에만 남은 같은 블록의 번호를 정규화한다.
 * 각 depth/run의 첫 번호는 수동 시작 번호로 보존한다.
 */
internal fun orderedListDeletionEdit(
    originalText: String,
    updatedText: String,
    deletedOriginalRange: TextRange,
    selection: TextRange,
    updatedChangeStart: Int,
): ListDepthEdit? {
    if (deletedOriginalRange.collapsed || originalText == updatedText) return null
    val deletedStart = deletedOriginalRange.min.coerceIn(0, originalText.length)
    val deletedEnd = deletedOriginalRange.max.coerceIn(deletedStart, originalText.length)
    if (deletedStart == deletedEnd) return null
    val deletedRange = deletedStart until deletedEnd
    val deletedText = originalText.substring(deletedStart, deletedEnd)
    val affectsOrderedStructure = markdownListPresentations(originalText).any { list ->
        list.isOrdered && (
            list.prefixRange.intersectsMarkdown(deletedRange) ||
                ('\n' in deletedText && list.range.intersectsMarkdown(deletedRange))
            )
    }
    if (!affectsOrderedStructure) return null

    val renumbered = renumberOrderedListBlockAfterDeletion(updatedText, updatedChangeStart)
    if (renumbered == updatedText) return null
    return singleListDepthEdit(
        original = updatedText,
        updated = renumbered,
        selection = TextRange(
            selection.start.coerceIn(0, renumbered.length),
            selection.end.coerceIn(0, renumbered.length),
        ),
    )
}

private fun renumberOrderedListBlockAfterDeletion(text: String, anchorOffset: Int): String {
    val lists = markdownListPresentations(text)
    if (lists.isEmpty()) return text
    val anchor = anchorOffset.coerceIn(0, text.length)
    val anchorIndex = lists.indexOfFirst { anchor <= it.range.last + 1 }
        .takeIf { it >= 0 }
        ?: lists.lastIndex

    var blockStart = anchorIndex
    while (blockStart > 0 && lists[blockStart].range.first == lists[blockStart - 1].range.last + 1) blockStart--
    var blockEnd = anchorIndex + 1
    while (blockEnd < lists.size && lists[blockEnd].range.first == lists[blockEnd - 1].range.last + 1) blockEnd++

    val replacements = mutableMapOf<IntRange, String>()
    val nextNumberByDepth = mutableMapOf<Int, Int>()
    for (index in blockStart until blockEnd) {
        val list = lists[index]
        nextNumberByDepth.keys.removeAll { it > list.depth }
        if (!list.isOrdered) {
            nextNumberByDepth.remove(list.depth)
            continue
        }
        val numberRange = orderedNumberRange(text, list) ?: continue
        val sourceNumber = text.substring(numberRange).toIntOrNull() ?: continue
        val number = nextNumberByDepth[list.depth] ?: sourceNumber
        replacements[numberRange] = number.toString()
        nextNumberByDepth[list.depth] = number + 1
    }

    return StringBuilder(text).apply {
        replacements.entries.sortedByDescending { it.key.first }.forEach { (range, number) ->
            replace(range.first, range.last + 1, number)
        }
    }.toString()
}

private fun orderedNumberRange(text: String, list: MarkdownListPresentation): IntRange? {
    if (!list.isOrdered) return null
    var end = list.markerRange.first
    while (end <= list.markerRange.last && text[end].isDigit()) end++
    return if (end > list.markerRange.first) list.markerRange.first until end else null
}

private fun singleListDepthEdit(
    original: String,
    updated: String,
    selection: TextRange,
): ListDepthEdit {
    var prefixLength = 0
    while (prefixLength < original.length && prefixLength < updated.length &&
        original[prefixLength] == updated[prefixLength]
    ) {
        prefixLength++
    }
    var suffixLength = 0
    while (suffixLength < original.length - prefixLength && suffixLength < updated.length - prefixLength &&
        original[original.lastIndex - suffixLength] == updated[updated.lastIndex - suffixLength]
    ) {
        suffixLength++
    }

    return ListDepthEdit(
        range = prefixLength until (original.length - suffixLength),
        replacement = updated.substring(prefixLength, updated.length - suffixLength),
        selection = selection,
    )
}

private const val LIST_INDENT = "    "
private val LIST_LINE_REGEX = Regex("^([ \\t]*)(?:[-*+]|\\d+\\.) ")
