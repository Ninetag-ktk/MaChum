package com.ninetag.machum.markdown.state

import androidx.compose.ui.text.TextRange
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.external.WorkspaceLinkDocument
import com.ninetag.machum.external.WorkspaceLinkPath
import com.ninetag.machum.external.WorkspaceLinkBlockDraft

enum class MarkdownLinkCompletionKind { FILE, HEADING, BLOCK }

enum class MarkdownLinkCompletionSyntax { WIKI, MARKDOWN }

data class MarkdownLinkCompletionRequest(
    val kind: MarkdownLinkCompletionKind,
    val syntax: MarkdownLinkCompletionSyntax,
    val query: String,
    val file: String? = null,
    val displayText: String? = null,
    val replacementStart: Int,
    val replacementEndExclusive: Int,
    val hasExistingAlias: Boolean = false,
)

internal data class MarkdownLinkCompletionLookupKey(
    val request: MarkdownLinkCompletionRequest,
    val targetText: String,
)

internal fun markdownLinkCompletionLookupKey(
    text: String,
    request: MarkdownLinkCompletionRequest,
): MarkdownLinkCompletionLookupKey = MarkdownLinkCompletionLookupKey(
    request = request,
    targetText = text.substring(
        request.replacementStart.coerceIn(0, text.length),
        request.replacementEndExclusive.coerceIn(request.replacementStart.coerceIn(0, text.length), text.length),
    ),
)

/** Preview caret movement is not editing; new drafts and explicit source editing may query. */
internal fun markdownLinkCompletionEditingRequest(
    text: String,
    selection: TextRange,
    explicitRawRange: IntRange? = null,
    rawMode: Boolean = false,
): MarkdownLinkCompletionRequest? {
    val request = markdownLinkCompletionRequest(text, selection) ?: return null
    // Embeds keep their existing source/completion flow; only ordinary link previews are gated.
    val bang = request.replacementStart - 3
    val escapedBang = text.take(bang.coerceAtLeast(0)).takeLastWhile { it == '\\' }.length % 2 == 1
    val wikiEmbed = request.syntax == MarkdownLinkCompletionSyntax.WIKI && text.getOrNull(bang) == '!' && !escapedBang
    if (rawMode || wikiEmbed || request.displayText != null || explicitRawRange?.let {
            request.replacementStart >= it.first && request.replacementEndExclusive <= it.last + 1
        } == true) return request
    val completed = when (request.syntax) {
        MarkdownLinkCompletionSyntax.WIKI -> {
            val close = text.indexOf("]]", request.replacementEndExclusive)
            close >= 0 && text.substring(request.replacementEndExclusive, close).none { it == '\n' || it == '\r' }
        }
        MarkdownLinkCompletionSyntax.MARKDOWN -> text.getOrNull(request.replacementEndExclusive) == ')'
    }
    return request.takeUnless { completed }
}

data class MarkdownLinkCompletionCandidate(
    val title: String,
    val detail: String,
    val replacement: String,
    val blockCreation: MarkdownBlockReferenceCreation? = null,
)

data class MarkdownBlockReferenceCreation(
    val path: WorkspaceLinkPath,
    val draft: WorkspaceLinkBlockDraft,
    val isCurrentDocument: Boolean,
)

/** A completion may carry a display alias after the target that ends in `^`. */
internal fun markdownBlockReferenceReplacement(replacement: String, id: String): String? {
    val targetEnd = firstUnescapedPipe(replacement, 0, replacement.length) ?: replacement.length
    if (targetEnd == 0 || replacement[targetEnd - 1] != '^') return null
    return replacement.substring(0, targetEnd) + id + replacement.substring(targetEnd)
}

data class MarkdownNavigationTarget(
    val id: Long,
    val headingPath: String? = null,
    val blockId: String? = null,
)

internal fun markdownNavigationOffset(text: String, target: MarkdownNavigationTarget): Int? {
    val document = WorkspaceLinkDocument.markdown(
        WorkspaceLinkPath(WorkspaceKind.PROJECT, "Current", "Current.md"),
        text,
    )
    return when {
        target.headingPath != null -> document.headings
            .firstOrNull { it.path.equals(target.headingPath, ignoreCase = true) }
            ?.sourceRange?.start
        target.blockId != null -> document.blocks
            .firstOrNull { it.id.equals(target.blockId, ignoreCase = true) }
            ?.sourceRange?.start
            ?.let { markdownBlockOwnerStart(text, it) }
        else -> null
    }
}

/** Returns the start of the block owning a `^id`, rather than the id token itself. */
internal fun markdownBlockOwnerStart(text: String, idStart: Int): Int {
    var ownerEnd = idStart
    val lineStart = text.lastIndexOf('\n', idStart - 1).let { it + 1 }
    if (isListLine(text.substring(lineStart, idStart))) return lineStart
    if (text.substring(lineStart, idStart).isBlank()) ownerEnd = lineStart
    while (ownerEnd > 0 && text[ownerEnd - 1].isWhitespace()) ownerEnd--
    if (ownerEnd == 0) return lineStart

    val blocks = MarkdownBlockParser.parse(text.substring(0, ownerEnd))
    if (blocks.isEmpty()) return lineStart
    val blockStart = blocks.dropLast(1).sumOf { it.toMarkdown().length + 1 }
    val owner = blocks.last()
    return if (owner is EditorBlock.Text) {
        blockStart + textBlockOwnerOffset(owner.toMarkdown())
    } else {
        blockStart
    }
}

private fun isListLine(line: String): Boolean =
    line.trimStart().matches(Regex("(?:[-+*]|\\d+[.)])\\s+.*"))

private fun isHeadingLine(line: String): Boolean =
    line.matches(Regex(" {0,3}#{1,6}(?:\\s+.*)?"))

private fun isHorizontalRuleLine(line: String): Boolean =
    line.matches(Regex(" {0,3}(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})"))

private fun textBlockOwnerOffset(text: String): Int {
    val lines = mutableListOf<String>()
    val starts = mutableListOf<Int>()
    var start = 0
    while (start <= text.length) {
        starts += start
        val newline = text.indexOf('\n', start)
        val end = if (newline >= 0) newline else text.length
        val contentEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
        lines += text.substring(start, contentEnd)
        if (newline < 0) break
        start = newline + 1
    }

    var line = lines.indexOfLast { it.isNotBlank() }
    if (line < 0) return 0
    val ownerLine = lines[line]
    when {
        isHeadingLine(ownerLine) || isHorizontalRuleLine(ownerLine) -> return starts[line]
        ownerLine.trimStart().startsWith(">") -> {
            while (line > 0 && lines[line - 1].trimStart().startsWith(">")) line--
        }
        isListLine(ownerLine) || ownerLine.firstOrNull()?.isWhitespace() == true -> {
            while (line > 0) {
                val previous = lines[line - 1]
                if (previous.isBlank() || (!isListLine(previous) && previous.firstOrNull()?.isWhitespace() != true)) break
                line--
            }
        }
        else -> {
            while (line > 0) {
                val previous = lines[line - 1]
                if (previous.isBlank() || isHeadingLine(previous) || isHorizontalRuleLine(previous) || isListLine(previous) ||
                    previous.trimStart().startsWith(">")
                ) break
                line--
            }
        }
    }
    return starts[line]
}

/** Finds the link target containing [cursor], using the whole target as the completion query. */
internal fun markdownLinkCompletionRequest(
    text: String,
    cursor: Int,
): MarkdownLinkCompletionRequest? {
    if (cursor !in 0..text.length) return null
    wikiCompletionRequest(text, cursor)?.let { return it }
    if (isCursorInWikiAlias(text, cursor)) return null
    return markdownCompletionRequest(text, cursor)
}

/** Selected text wrapped as `[[selection]]` becomes the display part after a target is chosen. */
internal fun markdownLinkCompletionRequest(
    text: String,
    selection: TextRange,
): MarkdownLinkCompletionRequest? {
    if (selection.collapsed) return markdownLinkCompletionRequest(text, selection.start)
    val start = selection.min
    val end = selection.max
    if (start < 2 || end + 2 > text.length) return null
    if (text.substring(start - 2, start) != "[[" || text.substring(end, end + 2) != "]]" ) return null
    val display = text.substring(start, end)
    if (display.isBlank() || '\n' in display || '\r' in display) return null
    if (firstUnescapedPipe(display, 0, display.length) != null) return null
    return MarkdownLinkCompletionRequest(
        kind = MarkdownLinkCompletionKind.FILE,
        syntax = MarkdownLinkCompletionSyntax.WIKI,
        query = "",
        displayText = display,
        replacementStart = start,
        replacementEndExclusive = end,
    )
}

private fun wikiCompletionRequest(text: String, cursor: Int): MarkdownLinkCompletionRequest? {
    val opening = text.lastIndexOf("[[", (cursor - 1).coerceAtLeast(0))
    if (opening < 0 || cursor < opening + 2) return null
    val closing = text.indexOf("]]", opening + 2).let { if (it < 0) text.length else it }
    if (cursor > closing || text.substring(opening + 2, closing).any { it == '\n' || it == '\r' }) return null
    val aliasSeparator = firstUnescapedPipe(text, opening + 2, closing)
    if (aliasSeparator != null && cursor > aliasSeparator) return null
    val targetEnd = aliasSeparator ?: closing

    val typed = text.substring(opening + 2, targetEnd)
    val (kind, file, query) = when {
        typed.startsWith('^') -> Triple(MarkdownLinkCompletionKind.BLOCK, null, typed.drop(1))
        "#^" in typed -> {
            val separator = typed.indexOf("#^")
            Triple(MarkdownLinkCompletionKind.BLOCK, typed.substring(0, separator).ifBlank { null }, typed.substring(separator + 2))
        }
        '#' in typed -> {
            val separator = typed.indexOf('#')
            Triple(MarkdownLinkCompletionKind.HEADING, typed.substring(0, separator).ifBlank { null }, typed.substring(separator + 1))
        }
        else -> Triple(MarkdownLinkCompletionKind.FILE, null, typed)
    }
    return MarkdownLinkCompletionRequest(
        kind = kind,
        syntax = MarkdownLinkCompletionSyntax.WIKI,
        query = query,
        file = file,
        replacementStart = opening + 2,
        replacementEndExclusive = targetEnd,
        hasExistingAlias = aliasSeparator != null,
    )
}

private fun firstUnescapedPipe(text: String, start: Int, endExclusive: Int): Int? {
    for (index in start until endExclusive) {
        if (text[index] != '|') continue
        var precedingBackslashes = 0
        var previous = index - 1
        while (previous >= start && text[previous] == '\\') {
            precedingBackslashes++
            previous--
        }
        if (precedingBackslashes % 2 == 0) return index
    }
    return null
}

private fun isCursorInWikiAlias(text: String, cursor: Int): Boolean {
    val opening = text.lastIndexOf("[[", (cursor - 1).coerceAtLeast(0))
    if (opening < 0 || cursor < opening + 2) return false
    val closing = text.indexOf("]]", opening + 2).let { if (it < 0) text.length else it }
    if (cursor > closing || text.substring(opening + 2, closing).any { it == '\n' || it == '\r' }) return false
    val aliasSeparator = firstUnescapedPipe(text, opening + 2, closing) ?: return false
    return cursor > aliasSeparator
}

private fun markdownCompletionRequest(text: String, cursor: Int): MarkdownLinkCompletionRequest? {
    val opening = text.lastIndexOf("](", (cursor - 1).coerceAtLeast(0))
    if (opening < 0 || cursor < opening + 2) return null
    val close = text.indexOf(')', opening + 2).let { if (it < 0) text.length else it }
    if (cursor > close || text.substring(opening + 2, close).any { it == '\n' || it == '\r' }) return null
    val typed = text.substring(opening + 2, close)
    if (typed.contains("://") || typed.startsWith("mailto:", ignoreCase = true)) return null
    return MarkdownLinkCompletionRequest(
        kind = MarkdownLinkCompletionKind.FILE,
        syntax = MarkdownLinkCompletionSyntax.MARKDOWN,
        query = typed,
        replacementStart = opening + 2,
        replacementEndExclusive = close,
    )
}
