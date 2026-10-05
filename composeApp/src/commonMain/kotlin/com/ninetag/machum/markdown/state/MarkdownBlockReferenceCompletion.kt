package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import com.ninetag.machum.external.insertWorkspaceBlockId
import com.ninetag.machum.external.newWorkspaceBlockId

/** Inserts a local block ID and its source link without replacing the live editor states. */
internal fun applyCurrentDocumentBlockReference(
    blocks: List<EditorBlock>,
    source: TextFieldState,
    request: MarkdownLinkCompletionRequest,
    candidate: MarkdownLinkCompletionCandidate,
    onCommit: (List<EditorBlock>) -> Unit,
): Boolean {
    val creation = candidate.blockCreation?.takeIf { it.isCurrentDocument } ?: return false
    if (!blocks.containsField(source) ||
        markdownLinkCompletionRequest(source.text.toString(), source.selection) != request
    ) return false
    val original = blocks.toMarkdown()
    val id = newWorkspaceBlockId(original)
    val replacement = markdownBlockReferenceReplacement(candidate.replacement, id) ?: return false
    val withId = insertWorkspaceBlockId(original, creation.draft, id) ?: return false
    val insertion = creation.draft.insertionPrefix + "^$id" + creation.draft.insertionSuffix
    // The scanner's validated insertion is authoritative, including blank lines and EOLs.
    if (withId != original.replaceRange(creation.draft.insertionOffset, creation.draft.insertionOffset, insertion)) {
        return false
    }
    val sourceText = source.text.toString()
    val originalSelection = source.selection
    val originalComposition = source.composition
    val editedSource = sourceText.replaceRange(request.replacementStart, request.replacementEndExclusive, replacement)
    val sourceOnly = blocks.replaceField(source, TextFieldState(editedSource)).toMarkdown()
    val sourceEdit = changedRange(original, sourceOnly)
    val insertionOffset = creation.draft.insertionOffset
    if (insertionOffset > sourceEdit.start && insertionOffset < sourceEdit.end) return false
    val expected = if (insertionOffset >= sourceEdit.end) {
        original.replaceRange(insertionOffset, insertionOffset, insertion)
            .replaceRange(sourceEdit.start, sourceEdit.end, sourceEdit.text)
    } else {
        sourceOnly.replaceRange(insertionOffset, insertionOffset, insertion)
    }

    var blockStart = 0
    var targetIndex = -1
    var targetTextOffset: Int? = null
    blocks.forEachIndexed { index, block ->
        val blockEnd = blockStart + block.toMarkdown().length
        if (creation.draft.sourceRange.start >= blockStart &&
            creation.draft.sourceRange.endExclusive <= blockEnd &&
            insertionOffset in blockStart..blockEnd
        ) {
            when (block) {
                is EditorBlock.Text -> {
                    // Blank markers have no persisted offset; they cannot own a scanner draft.
                    if (EditorBlock.BLANK_LINE_MARKER !in block.textFieldState.text) {
                        targetIndex = index
                        targetTextOffset = insertionOffset - blockStart
                    }
                }
                is EditorBlock.Table, is EditorBlock.Callout -> {
                    if (creation.draft.sourceRange.start == blockStart &&
                        creation.draft.sourceRange.endExclusive == blockEnd && insertionOffset == blockEnd
                    ) targetIndex = index
                }
                else -> Unit
            }
        }
        blockStart = blockEnd + 1
    }
    if (targetIndex < 0) return false
    val textOffset = targetTextOffset
    val target = blocks[targetIndex]
    val nextBlocks = if (textOffset == null) {
        // The list join supplies the insertion's first newline. Keep every other scanner byte.
        if (!insertion.startsWith('\n')) return false
        blocks.toMutableList().apply {
            add(targetIndex + 1, EditorBlock.Text(textFieldState = TextFieldState(insertion.drop(1))))
        }
    } else blocks
    val snapshot = Snapshot.takeMutableSnapshot()
    try {
        val matches = snapshot.enter {
            if (source.text.toString() != sourceText || source.selection != originalSelection ||
                source.composition != originalComposition) return@enter false
            val targetState = (target as? EditorBlock.Text)?.textFieldState
            val sourceChange = FieldChange(request.replacementStart, request.replacementEndExclusive, replacement)
            if (targetState === source) {
                listOf(sourceChange, FieldChange(textOffset!!, textOffset, insertion))
                    .sortedByDescending { it.start }
                    .forEach { change -> source.edit { replace(change.start, change.end, change.text) } }
            } else {
                source.edit { replace(sourceChange.start, sourceChange.end, sourceChange.text) }
                if (targetState != null) targetState.edit { replace(textOffset!!, textOffset, insertion) }
            }
            val caret = request.replacementStart + replacement.length +
                if (targetState === source && textOffset!! <= request.replacementStart) insertion.length else 0
            source.edit { selection = TextRange(caret) }
            if (nextBlocks.toMarkdown() != expected) false else {
                onCommit(nextBlocks)
                true
            }
        }
        if (!matches) return false
        snapshot.apply().check()
        return true
    } finally {
        snapshot.dispose()
    }
}

private data class FieldChange(val start: Int, val end: Int, val text: String)

private fun changedRange(before: String, after: String): FieldChange {
    val start = before.commonPrefixWith(after).length
    val tail = before.substring(start).commonSuffixWith(after.substring(start)).length
    return FieldChange(start, before.length - tail, after.substring(start, after.length - tail))
}

private fun List<EditorBlock>.containsField(field: TextFieldState): Boolean = any { block ->
    when (block) {
        is EditorBlock.Text -> block.textFieldState === field
        is EditorBlock.Callout -> block.titleState === field || block.bodyBlocks.containsField(field)
        is EditorBlock.Table -> block.headerStates.any { it === field } || block.rowStates.any { row -> row.any { it === field } }
        is EditorBlock.Code -> block.codeState === field
        else -> false
    }
}

private fun List<EditorBlock>.replaceField(before: TextFieldState, after: TextFieldState): List<EditorBlock> = map { block ->
    when (block) {
        is EditorBlock.Text -> if (block.textFieldState === before) block.copy(textFieldState = after) else block
        is EditorBlock.Callout -> block.copy(
            titleState = if (block.titleState === before) after else block.titleState,
            bodyBlocks = block.bodyBlocks.replaceField(before, after),
        )
        is EditorBlock.Table -> block.copy(
            headerStates = block.headerStates.map { if (it === before) after else it },
            rowStates = block.rowStates.map { row -> row.map { if (it === before) after else it } },
        )
        is EditorBlock.Code -> if (block.codeState === before) block.copy(codeState = after) else block
        else -> block
    }
}
