package com.ninetag.machum.external

import kotlin.random.Random

/** An exact raw Markdown owner and the only position at which a new ID may be inserted. */
data class WorkspaceLinkBlockDraft(
    val sourceRange: WorkspaceLinkSourceRange,
    val expectedText: String,
    val insertionOffset: Int,
    val insertionPrefix: String,
    val insertionSuffix: String = "",
) {
    val preview: String get() = expectedText.replace(Regex("\\s+"), " ").trim().take(100)
}

fun scanMarkdownBlockDrafts(raw: String): List<WorkspaceLinkBlockDraft> {
    val body = NoteFile.parse(raw).body
    return scanMarkdownBlockDrafts(raw, body, WorkspaceLinkScanner.excludedCode(body, includeInlineCode = false))
}

internal fun scanMarkdownBlockDrafts(
    raw: String,
    body: String,
    excluded: BooleanArray,
    onExistingBlock: (WorkspaceLinkBlock, String) -> Unit = { _, _ -> },
): List<WorkspaceLinkBlockDraft> {
    // An unclosed frontmatter fence has no safely identifiable body.
    if (Regex("^---(?:\\r\\n|[\\r\\n])").containsMatchIn(raw.removePrefix("\uFEFF")) && body == raw.removePrefix("\uFEFF")) return emptyList()
    val offset = raw.length - body.length
    val lines = mutableListOf<WorkspaceDraftLine>()
    forEachLine(body) { start, end ->
        lines += WorkspaceDraftLine(start, end, body.substring(start, end), lineIsExcluded(excluded, start, end))
    }
    val eol = if ("\r\n" in raw) "\r\n" else if ('\r' in raw && '\n' !in raw) "\r" else "\n"
    val drafts = mutableListOf<WorkspaceLinkBlockDraft>()
    var i = 0
    while (i < lines.size) {
        val first = lines[i]
        if (first.text.isBlank() || first.excluded || first.indent >= 4 || isDraftBoundary(first.text) || standaloneBlockId.matches(first.text)) {
            i++
            continue
        }
        val start = i
        var structured = false
        var simple = true
        when {
            quoteLine.containsMatchIn(first.text) -> {
                structured = true
                val depth = quoteLine.find(first.text)!!.groupValues[1].length
                i++
                while (i < lines.size && quoteLine.containsMatchIn(lines[i].text)) {
                    val nextCallout = calloutLine.find(lines[i].text)
                    if (nextCallout != null && nextCallout.groupValues[1].length <= depth) break
                    i++
                }
            }
            isDraftTableStart(lines, i) -> {
                structured = true
                i += 2
                while (i < lines.size && lines[i].text.trimStart().startsWith('|')) i++
            }
            listLine.containsMatchIn(first.text) -> {
                simple = listContentLine.containsMatchIn(first.text)
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.text.isBlank()) {
                        if (i + 1 < lines.size && lines[i + 1].text.isNotBlank() && lines[i + 1].indent > first.indent) {
                            simple = false
                            i++
                            continue
                        }
                        break
                    }
                    if (next.indent > first.indent) {
                        simple = false
                        i++
                    } else if (listLine.containsMatchIn(next.text) || isDraftBoundary(next.text) || quoteLine.containsMatchIn(next.text) || next.excluded || isDraftTableStart(lines, i)) {
                        break
                    } else {
                        simple = false
                        i++
                    }
                }
            }
            else -> {
                i++
                while (i < lines.size && lines[i].text.isNotBlank() && !lines[i].excluded && lines[i].indent < 4 &&
                    !isDraftBoundary(lines[i].text) && !listLine.containsMatchIn(lines[i].text) &&
                    !quoteLine.containsMatchIn(lines[i].text) && !standaloneBlockId.matches(lines[i].text) && !isDraftTableStart(lines, i)) i++
                if (i < lines.size && setextLine.matches(lines[i].text)) {
                    i++
                    continue
                }
            }
        }
        var following = i
        while (following < lines.size && lines[following].text.isBlank()) following++
        val last = lines[i - 1]
        val identified = (start until i).mapNotNull {
            val line = lines[it]
            WorkspaceLinkScanner.parseBlock(body, line.start, line.end, offset)?.also { block ->
                val ownerStart = if (listLine.containsMatchIn(line.text.trimStart())) line.start else first.start
                onExistingBlock(block, raw.substring(offset + ownerStart, block.sourceRange.start).trimEnd())
            }
        }
        val standalone = lines.getOrNull(following)?.takeIf { standaloneBlockId.matches(it.text) }
            ?.let { WorkspaceLinkScanner.parseBlock(body, it.start, it.end, offset) }
        standalone?.let { block ->
            var owner = start
            if (listLine.containsMatchIn(first.text.trimStart())) {
                while (owner > 0 && lines[owner - 1].text.isNotBlank() &&
                    (listLine.containsMatchIn(lines[owner - 1].text.trimStart()) || lines[owner - 1].indent > 0)) owner--
            }
            onExistingBlock(block, raw.substring(offset + lines[owner].start, offset + last.end))
        }
        if (!simple || identified.isNotEmpty() || standalone != null) continue
        if (!structured && last.text.trimEnd().takeLastWhile { it == '\\' }.length % 2 == 1) continue
        val range = WorkspaceLinkSourceRange(offset + first.start, offset + last.end)
        val insertion = if (structured) range.endExclusive else offset + last.start + last.text.trimEnd().length
        drafts += WorkspaceLinkBlockDraft(
            sourceRange = range,
            expectedText = raw.substring(range.start, range.endExclusive),
            insertionOffset = insertion,
            insertionPrefix = if (structured) eol + eol else " ",
            insertionSuffix = if (structured && i < lines.size && lines[i].text.isNotBlank()) eol else "",
        )
    }
    return drafts
}

/** No writes: callers can group this validated insertion with their own document transaction. */
fun insertWorkspaceBlockId(raw: String, draft: WorkspaceLinkBlockDraft, id: String): String? {
    if (id.isEmpty() || id.any { it !in 'a'..'z' && it !in '0'..'9' }) return null
    val range = draft.sourceRange
    if (range.endExclusive > raw.length || draft.insertionOffset !in range.start..range.endExclusive ||
        raw.substring(range.start, range.endExclusive) != draft.expectedText) return null
    if (workspaceBlockIds(raw).any { it.equals(id, ignoreCase = true) }) return null
    if (draft !in scanMarkdownBlockDrafts(raw)) return null
    return raw.substring(0, draft.insertionOffset) + draft.insertionPrefix + "^$id" + draft.insertionSuffix + raw.substring(draft.insertionOffset)
}

fun newWorkspaceBlockId(raw: String): String {
    val existing = workspaceBlockIds(raw).mapTo(mutableSetOf()) { it.lowercase() }
    val characters = "abcdefghijklmnopqrstuvwxyz0123456789"
    while (true) {
        val id = buildString { repeat(8) { append(characters[Random.nextInt(characters.length)]) } }
        if (id !in existing) return id
    }
}

private fun workspaceBlockIds(raw: String): List<String> {
    val body = NoteFile.parse(raw).body
    val excluded = WorkspaceLinkScanner.excludedCode(body)
    val ids = mutableListOf<String>()
    forEachLine(body) { start, end ->
        if (!lineIsExcluded(excluded, start, end)) WorkspaceLinkScanner.parseBlock(body, start, end, 0)?.let { ids += it.id }
    }
    return ids
}

private data class WorkspaceDraftLine(val start: Int, val end: Int, val text: String, val excluded: Boolean) {
    val indent: Int get() = text.takeWhile { it == ' ' }.length.let { if (text.startsWith('\t')) 4 else it }
}

private fun isDraftTableStart(lines: List<WorkspaceDraftLine>, index: Int): Boolean =
    index + 1 < lines.size && lines[index].text.trimStart().startsWith('|') &&
        lines[index + 1].text.trim().trim('|').split('|').let { cells ->
            cells.isNotEmpty() && cells.all { it.trim().matches(Regex(":?-{3,}:?")) }
        }

private fun isDraftBoundary(text: String): Boolean = headingLine.matches(text) || horizontalRuleLine.matches(text) || setextLine.matches(text) || isStandaloneDraftEmbed(text)

private fun isStandaloneDraftEmbed(text: String): Boolean {
    val trimmed = text.trim()
    if (!trimmed.startsWith("![[")) return false
    val close = findWikiClose(trimmed, 3)
    return close == trimmed.length - 2 && trimmed.substring(3, close).substringBefore('|').isNotBlank()
}
private val headingLine = Regex(" {0,3}#{1,6}(?:[ \\t]+.*)?")
private val horizontalRuleLine = Regex(" {0,3}(?:(?:\\*[ \\t]*){3,}|(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,})")
private val setextLine = Regex(" {0,3}(?:=+|-+)[ \\t]*")
private val quoteLine = Regex("^ {0,3}(>+)")
private val calloutLine = Regex("^ {0,3}(>+) ?\\[![\\w-]+]")
private val listLine = Regex("^ {0,3}(?:[-+*]|\\d+[.)])(?:[ \\t]+.*)?$")
private val listContentLine = Regex("^ {0,3}(?:[-+*]|\\d+[.)])[ \\t]+\\S.*")
private val standaloneBlockId = Regex("[ \\t]*\\^[\\p{L}\\p{N}_-]+[ \\t]*")
