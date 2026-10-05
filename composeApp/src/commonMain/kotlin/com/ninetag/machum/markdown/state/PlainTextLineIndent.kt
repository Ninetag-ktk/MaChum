package com.ninetag.machum.markdown.state

import androidx.compose.ui.text.TextRange

/** Adjusts physical body lines in one edit; protected Markdown lines leave the whole selection intact. */
internal fun plainTextLineIndentEdit(
    text: String,
    selection: TextRange,
    outdent: Boolean,
): ListDepthEdit? {
    val start = selection.start.coerceIn(0, text.length)
    val end = selection.end.coerceIn(0, text.length)
    val firstOffset = minOf(start, end)
    val lastOffset = if (start == end) firstOffset else maxOf(start, end) - 1
    val firstLineStart = if (firstOffset == 0) 0 else text.lastIndexOf('\n', firstOffset - 1) + 1
    val lastLineEnd = text.indexOf('\n', lastOffset).let { if (it < 0) text.length else it }
    val replacement = StringBuilder()
    var lineStart = firstLineStart
    var mappedStart = start
    var mappedEnd = end
    var changed = false

    while (lineStart <= lastLineEnd) {
        val lineEnd = text.indexOf('\n', lineStart).let {
            if (it < 0 || it > lastLineEnd) lastLineEnd else it
        }
        val line = text.substring(lineStart, lineEnd)
        val body = line.trimStart(' ', '\t', '\u3000')
        if (body == EditorBlock.BLANK_LINE_MARKER ||
            (body.isNotEmpty() && !isPlainParagraphLine(body))
        ) return null

        val removed = if (outdent) line.take(2).takeWhile { it == ' ' }.length else 0
        val added = if (outdent) 0 else 2
        changed = changed || removed > 0 || added > 0
        replacement.append(if (outdent) line.drop(removed) else "  $line")
        // Map each endpoint from its original position, preserving reversed selections and prefix carets.
        if (start >= lineStart) mappedStart += added - minOf(removed, start - lineStart)
        if (end >= lineStart) mappedEnd += added - minOf(removed, end - lineStart)
        if (lineEnd == lastLineEnd) break
        replacement.append('\n')
        lineStart = lineEnd + 1
    }
    if (!changed) return null
    return ListDepthEdit(
        firstLineStart until lastLineEnd,
        replacement.toString(),
        TextRange(mappedStart, mappedEnd),
    )
}
