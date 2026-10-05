package com.ninetag.machum.markdown.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextRange
import com.ninetag.machum.external.clipEntryOf
import com.ninetag.machum.external.readClipboardText
import com.ninetag.machum.external.rememberClipboardHasText
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.EditorHistoryBoundary
import com.ninetag.machum.markdown.state.ListDepthEdit
import com.ninetag.machum.markdown.state.extractMarkdown
import com.ninetag.machum.markdown.state.listDepthEdit
import com.ninetag.machum.markdown.state.markdownOperationTextRange
import com.ninetag.machum.markdown.state.plainTextLineIndentEdit
import com.ninetag.machum.markdown.state.toMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class EditorQuickBarActions(
    val enabled: (EditorQuickBarCommand) -> Boolean,
    val execute: (EditorQuickBarCommand) -> Unit,
)

@Composable
internal fun rememberEditorQuickBarActions(
    targets: EditorQuickBarTargets,
    blocks: List<EditorBlock>,
    documentSelection: MutableState<DocumentSelection>,
    isActive: Boolean,
    documentRevision: Any,
    hasComposition: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    historyBoundary: EditorHistoryBoundary,
    onUndo: () -> Boolean,
    onRedo: () -> Boolean,
    onReplaceDocumentSelection: (DocumentSelection.Multi, String) -> Unit,
): EditorQuickBarActions {
    val clipboard = LocalClipboard.current
    val clipboardHasText = rememberClipboardHasText(clipboard, isActive)
    val scope = rememberCoroutineScope()
    val latestBlocks by rememberUpdatedState(blocks)
    val latestActive by rememberUpdatedState(isActive)
    val latestRevision by rememberUpdatedState(documentRevision)
    val latestHasComposition by rememberUpdatedState(hasComposition)
    val latestUndo by rememberUpdatedState(onUndo)
    val latestRedo by rememberUpdatedState(onRedo)
    val latestReplaceSelection by rememberUpdatedState(onReplaceDocumentSelection)
    var busy by remember { mutableStateOf(false) }

    fun indentEdit(target: EditorQuickBarTarget?, outdent: Boolean): ListDepthEdit? {
        if (target?.supportsLists != true) return null
        val text = target.state.text.toString()
        val selection = target.state.selection
        return listDepthEdit(text, selection, outdent) ?: plainTextLineIndentEdit(text, selection, outdent)
    }

    fun selectedText(): String? {
        val multi = documentSelection.value as? DocumentSelection.Multi
        if (multi != null) return extractMarkdown(latestBlocks, multi).takeIf { it.isNotEmpty() }
        val target = targets.focused ?: return null
        target.selectionCommands?.copy()?.let { return it.takeIf(String::isNotEmpty) }
        val state = target.state
        if (state.selection.collapsed) return null
        val text = state.text.toString()
        val range = if (target.usesMarkdownPreview) markdownOperationTextRange(text, state.selection) else state.selection
        val selected = text.substring(range.min, range.max)
        return (if (target.usesMarkdownPreview) selected.replace(EditorBlock.BLANK_LINE_MARKER, "") else selected)
            .takeIf(String::isNotEmpty)
    }

    fun enabled(command: EditorQuickBarCommand): Boolean {
        val target = targets.focused
        val multi = documentSelection.value is DocumentSelection.Multi
        if (!latestActive || busy || latestHasComposition || target?.state?.composition != null) return false
        if (target == null && !multi) return false
        return when (command) {
            EditorQuickBarCommand.UNDO -> canUndo
            EditorQuickBarCommand.REDO -> canRedo
            EditorQuickBarCommand.DL -> !multi && target?.canInsertDl == true
            EditorQuickBarCommand.INDENT, EditorQuickBarCommand.OUTDENT -> !multi &&
                indentEdit(target, outdent = command == EditorQuickBarCommand.OUTDENT) != null
            EditorQuickBarCommand.COPY, EditorQuickBarCommand.CUT -> selectedText() != null
            EditorQuickBarCommand.PASTE -> clipboardHasText && (multi || target != null)
        }
    }

    fun execute(command: EditorQuickBarCommand) {
        if (!enabled(command)) return
        val target = targets.focused
        when (command) {
            EditorQuickBarCommand.UNDO -> { latestUndo(); return }
            EditorQuickBarCommand.REDO -> { latestRedo(); return }
            EditorQuickBarCommand.DL -> {
                historyBoundary.markNextChangeAtomic()
                target?.insertDl?.invoke()
                return
            }
            EditorQuickBarCommand.INDENT, EditorQuickBarCommand.OUTDENT -> {
                val state = target?.state ?: return
                val edit = indentEdit(target, outdent = command == EditorQuickBarCommand.OUTDENT) ?: return
                historyBoundary.markNextChangeAtomic()
                state.edit {
                    replace(edit.range.first, edit.range.last + 1, edit.replacement)
                    selection = edit.selection
                }
                return
            }
            else -> Unit
        }
        val revision = latestRevision
        val focusRevision = targets.focusRevision
        val markdown = latestBlocks.toMarkdown()
        val selection = documentSelection.value
        val stateText = target?.state?.text?.toString()
        val stateSelection = target?.state?.selection
        val usesMarkdownPreview = target?.usesMarkdownPreview
        val specialized = target?.selectionCommands
        val specializedSnapshot = specialized?.snapshot()
        val selected = selectedText()
        fun isCurrent(): Boolean = latestActive && latestRevision == revision &&
            !latestHasComposition && targets.focusRevision == focusRevision &&
            targets.focused === target && latestBlocks.toMarkdown() == markdown &&
            documentSelection.value == selection && target?.state?.text?.toString() == stateText &&
            target?.state?.selection == stateSelection && target?.state?.composition == null &&
            target?.usesMarkdownPreview == usesMarkdownPreview &&
            specialized?.snapshot() == specializedSnapshot

        busy = true
        scope.launch {
            try {
                val replacement = if (command == EditorQuickBarCommand.PASTE) {
                    readClipboardText(clipboard)?.takeIf(String::isNotEmpty) ?: return@launch
                } else {
                    clipboard.setClipEntry(clipEntryOf(selected ?: return@launch))
                    if (command == EditorQuickBarCommand.COPY) return@launch
                    ""
                }
                if (!isCurrent()) return@launch
                if (selection is DocumentSelection.Multi) {
                    latestReplaceSelection(selection, replacement)
                } else if (specialized != null) {
                    if (command == EditorQuickBarCommand.CUT) specialized.cut() else specialized.paste(replacement)
                } else {
                    val state = target?.state ?: return@launch
                    val range = if (command == EditorQuickBarCommand.CUT && target.usesMarkdownPreview) {
                        markdownOperationTextRange(stateText.orEmpty(), state.selection)
                    } else state.selection
                    val previous = state.text.toString()
                    if (previous.replaceRange(range.min, range.max, replacement) != previous) {
                        historyBoundary.markNextChangeAtomic()
                    }
                    state.edit {
                        replace(range.min, range.max, replacement)
                        this.selection = TextRange(range.min + replacement.length)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Failed clipboard access preserves the document, especially Cut.
            } finally {
                busy = false
            }
        }
    }
    return EditorQuickBarActions(::enabled, ::execute)
}
