package com.ninetag.machum.markdown.state

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextRange
import com.ninetag.machum.external.NoteFile
import com.ninetag.machum.external.WorkspaceLinkBlock
import com.ninetag.machum.external.WorkspaceLinkHeading

sealed interface MarkdownEmbedPreview {
    data class Document(val markdown: String) : MarkdownEmbedPreview
    data class Image(val image: ImageBitmap, val description: String) : MarkdownEmbedPreview
    data class Attachment(val name: String) : MarkdownEmbedPreview
    data class Unavailable(val message: String) : MarkdownEmbedPreview
}

/** Only a complete standalone Embed gets a document preview; its display text remains in the source. */
internal fun standaloneMarkdownEmbedTarget(source: String): String? {
    val target = embedLinkRegex.matchEntire(source)?.groupValues?.get(1) ?: return null
    return target.takeIf { it.substringBefore('|').isNotBlank() }
}

/** An embed row being edited must keep its input state even after an existing paragraph. */
internal fun markdownEmbedSourceAtSelection(source: String, selection: TextRange): String? {
    val start = source.lastIndexOf('\n', selection.min - 1) + 1
    val end = source.indexOf('\n', selection.max).takeIf { it >= 0 } ?: source.length
    val row = source.substring(start, end)
    return row.takeIf { it == "![[]]" || standaloneMarkdownEmbedTarget(it) != null }
}

internal fun extractMarkdownEmbed(
    raw: String,
    heading: WorkspaceLinkHeading? = null,
    block: WorkspaceLinkBlock? = null,
): String {
    val selected = when {
        block != null -> {
            val normalized = raw.replace("\r\n", "\n")
            val idStart = raw.take(block.sourceRange.start).replace("\r\n", "\n").length
            normalized.substring(markdownBlockOwnerStart(normalized, idStart), idStart).trimEnd()
        }
        heading != null -> {
            val document = com.ninetag.machum.external.WorkspaceLinkDocument.markdown(
                com.ninetag.machum.external.WorkspaceLinkPath(
                    com.ninetag.machum.external.WorkspaceKind.GENERAL,
                    "embed",
                    "embed.md",
                ),
                raw,
            )
            val nextHeading = document.headings.firstOrNull {
                it.sourceRange.start > heading.sourceRange.start && it.level <= heading.level
            }
            raw.substring(
                raw.lineStart(heading.sourceRange.start),
                nextHeading?.sourceRange?.start?.let(raw::lineStart) ?: raw.length,
            ).trimEnd()
        }
        else -> NoteFile.parse(raw).body
    }
    return if (selected.length <= MAX_EMBED_MARKDOWN_CHARS) selected
    else selected.take(MAX_EMBED_MARKDOWN_CHARS).trimEnd() + "\n…"
}

private fun String.lineStart(offset: Int): Int =
    lastIndexOf('\n', (offset - 1).coerceAtMost(lastIndex)).let { if (it < 0) 0 else it + 1 }

private const val MAX_EMBED_MARKDOWN_CHARS = 20_000
