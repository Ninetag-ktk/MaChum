package com.ninetag.machum.markdown.state

/** Clickable Markdown targets in source coordinates. */
internal sealed interface MarkdownInteraction {
    val activationRange: IntRange
    val syntaxRange: IntRange

    data class TaskCheckbox(
        override val activationRange: IntRange,
        val prefixRange: IntRange,
        val stateOffset: Int,
        val checked: Boolean,
    ) : MarkdownInteraction {
        override val syntaxRange: IntRange get() = prefixRange
    }

    data class ExternalLink(
        override val activationRange: IntRange,
        val url: String,
        override val syntaxRange: IntRange = activationRange,
    ) : MarkdownInteraction

    data class InternalLink(
        override val activationRange: IntRange,
        val target: String,
        override val syntaxRange: IntRange = activationRange,
    ) : MarkdownInteraction

    data class Embed(
        override val activationRange: IntRange,
        val target: String,
        override val syntaxRange: IntRange,
    ) : MarkdownInteraction
}

internal data class VisualTaskCheckbox(val range: IntRange, val checked: Boolean)

internal data class VisualExternalLinkIndicator(
    val range: IntRange,
    val interaction: MarkdownInteraction.ExternalLink,
)

internal const val EXTERNAL_LINK_INDICATOR_PLACEHOLDER: String = "\u2003"
internal const val INTERNAL_LINK_ACTION_PLACEHOLDER: String = "\u2002"

internal fun MarkdownInteraction.rawEditOffset(): Int = when (this) {
    is MarkdownInteraction.TaskCheckbox -> stateOffset
    is MarkdownInteraction.ExternalLink,
    is MarkdownInteraction.InternalLink,
    is MarkdownInteraction.Embed -> activationRange.first
}

/** Tracks edits inside one explicitly opened link; edits outside it invalidate the anchor. */
internal class MarkdownLinkRawEdit private constructor(val prefix: String, val suffix: String) {
    private var closed = false

    fun range(text: String, selectionStart: Int, selectionEnd: Int): IntRange? {
        if (closed) return null
        val range = activeRange(text, selectionStart, selectionEnd)
        // Cleanup effects can lag behind a rapid caret exit and re-entry by one frame.
        if (range == null) closed = true
        return range
    }

    private fun activeRange(text: String, selectionStart: Int, selectionEnd: Int): IntRange? {
        if (!text.startsWith(prefix) || !text.endsWith(suffix)) return null
        val trackedEnd = text.length - suffix.length
        val interactions = markdownInteractions(text)
        val token = interactions.firstOrNull { it.isOrdinaryLink() && it.syntaxRange.first == prefix.length }
        val end = token?.syntaxRange?.last?.plus(1) ?: trackedEnd
        if (end > trackedEnd) return null
        if (token != null && selectionStart == selectionEnd && selectionStart == end) return null
        // A broken token must not absorb a newly inserted complete link into its raw region.
        if (token == null && interactions.any {
                it.syntaxRange.first in (prefix.length + 1) until trackedEnd &&
                    (it !is MarkdownInteraction.ExternalLink || it.syntaxRange != it.activationRange)
            }
        ) return null
        if (end <= prefix.length || minOf(selectionStart, selectionEnd) < prefix.length ||
            maxOf(selectionStart, selectionEnd) > end
        ) return null
        return prefix.length until end
    }

    companion object {
        fun open(text: String, range: IntRange): MarkdownLinkRawEdit =
            MarkdownLinkRawEdit(text.substring(0, range.first), text.substring(range.last + 1))
    }
}

internal fun MarkdownInteraction.isOrdinaryLink(): Boolean =
    this is MarkdownInteraction.ExternalLink || this is MarkdownInteraction.InternalLink

/**
 * Extracts interaction targets without changing Markdown parsing or the current raw exposure policy.
 * Ranges point at the visible label/checkbox, while targets retain the source value.
 */
internal fun markdownInteractions(text: String): List<MarkdownInteraction> {
    if (text.isEmpty()) return emptyList()

    val occupied = mutableListOf<IntRange>()
    val interactions = mutableListOf<MarkdownInteraction>()
    val codeRanges = inlineCodeRegex.findAll(text).map(MatchResult::range).toList()

    taskPrefixRegex.findAll(text).forEach { match ->
        val marker = checkNotNull(match.groups[2])
        val state = checkNotNull(match.groups[3])
        interactions += MarkdownInteraction.TaskCheckbox(
            activationRange = marker.range.first..(state.range.last + 1),
            prefixRange = match.range,
            stateOffset = state.range.first,
            checked = state.value.equals("x", ignoreCase = true),
        )
        occupied += match.range
    }

    embedLinkRegex.findAll(text).forEach { match ->
        if (codeRanges.any(match.range::intersectsMarkdown) || text.isMarkdownEscaped(match.range.first)) {
            return@forEach
        }
        val inner = checkNotNull(match.groups[1])
        val target = inner.value.substringBefore('|').trim()
        if (target.isNotEmpty()) {
            interactions += MarkdownInteraction.Embed(
                activationRange = inner.range,
                target = target,
                syntaxRange = match.range,
            )
            occupied += match.range
        }
    }

    wikiLinkRegex.findAll(text).forEach { match ->
        if (codeRanges.any(match.range::intersectsMarkdown) || text.isMarkdownEscaped(match.range.first)) {
            return@forEach
        }
        val inner = checkNotNull(match.groups[1])
        val separator = inner.value.indexOf('|')
        val target = inner.value.substringBefore('|').trim()
        val labelStart = if (separator < 0) inner.range.first else inner.range.first + separator + 1
        val labelRange = labelStart..inner.range.last
        if (target.isNotEmpty() && !labelRange.isEmpty()) {
            interactions += MarkdownInteraction.InternalLink(labelRange, target, match.range)
            occupied += match.range
        }
    }

    markdownLinkRegex.findAll(text).forEach { match ->
        if (codeRanges.any(match.range::intersectsMarkdown) || text.isMarkdownEscaped(match.range.first)) {
            return@forEach
        }
        val label = checkNotNull(match.groups[1])
        val target = checkNotNull(match.groups[2]).value
        interactions += if (
            target.startsWith("https://", ignoreCase = true) ||
            target.startsWith("http://", ignoreCase = true)
        ) {
            MarkdownInteraction.ExternalLink(
                activationRange = label.range,
                url = target,
                syntaxRange = match.range,
            )
        } else {
            MarkdownInteraction.InternalLink(label.range, target, match.range)
        }
        occupied += match.range
    }

    bareExternalUrlRegex.findAll(text).forEach { match ->
        if (occupied.none(match.range::intersectsMarkdown) && codeRanges.none(match.range::intersectsMarkdown)) {
            val trimmed = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            if (trimmed.isNotEmpty()) {
                interactions += MarkdownInteraction.ExternalLink(
                    activationRange = match.range.first until (match.range.first + trimmed.length),
                    url = trimmed,
                )
            }
        }
    }

    return interactions.sortedBy { it.activationRange.first }
}

internal fun MarkdownInteraction.activationRange(isRaw: Boolean): IntRange? =
    activationRange.takeUnless { isRaw }

internal val markdownTaskPrefixRegex = Regex("^([ \\t]*)([-*+]|\\d+\\.)[ \\t]{1,4}\\[([ xX])][ \\t]+")

private val taskPrefixRegex = Regex("(?m)^([ \\t]*)([-*+]|\\d+\\.)[ \\t]{1,4}\\[([ xX])][ \\t]+")
internal val embedLinkRegex = Regex("!\\[\\[([^]\\r\\n]+)]]")
private val wikiLinkRegex = Regex("(?<!!)\\[\\[([^]\\n]+)]]")
private val markdownLinkRegex = Regex("(?<!!)\\[([^\\[\\]\\n]+)]\\(([^)\\s]+)\\)")
private val bareExternalUrlRegex = Regex("https?://[^\\s<>()]+", RegexOption.IGNORE_CASE)
private val inlineCodeRegex = Regex("`[^`\\n]+`")

private fun String.isMarkdownEscaped(index: Int): Boolean {
    var cursor = index - 1
    var backslashCount = 0
    while (cursor >= 0 && this[cursor] == '\\') {
        backslashCount++
        cursor--
    }
    return backslashCount % 2 == 1
}
