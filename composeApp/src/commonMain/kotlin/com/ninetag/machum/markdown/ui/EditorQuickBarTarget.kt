package com.ninetag.machum.markdown.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/** Table selection uses its existing TSV operations, rather than a new editor engine. */
internal class EditorQuickBarSelectionCommands(
    val snapshot: () -> Any?,
    val copy: () -> String?,
    val cut: () -> Unit,
    val paste: (String) -> Unit,
)

internal class EditorQuickBarTarget(val state: TextFieldState) {
    var supportsLists: Boolean by mutableStateOf(false)
    var usesMarkdownPreview: Boolean by mutableStateOf(false)
    var canInsertDl: Boolean by mutableStateOf(false)
        private set
    var insertDl: (() -> Unit)? = null
        set(value) {
            field = value
            canInsertDl = value != null
        }
    var selectionCommands: EditorQuickBarSelectionCommands? = null
}

internal class EditorQuickBarTargets {
    var focused by mutableStateOf<EditorQuickBarTarget?>(null)
        private set
    var focusRevision: Long = 0
        private set

    fun focus(target: EditorQuickBarTarget) {
        if (focused !== target) {
            focusRevision++
            focused = target
        }
    }

    fun blur(target: EditorQuickBarTarget) {
        if (focused === target) {
            focusRevision++
            focused = null
        }
    }
}

internal val LocalEditorQuickBarTargets = compositionLocalOf<EditorQuickBarTargets?> { null }

@Composable
internal fun Modifier.editorQuickBarTarget(
    state: TextFieldState,
    supportsLists: Boolean = false,
    usesMarkdownPreview: Boolean = false,
    insertDl: (() -> Unit)? = null,
    selectionCommands: EditorQuickBarSelectionCommands? = null,
): Modifier {
    val targets = LocalEditorQuickBarTargets.current ?: return this
    val target = remember(state) { EditorQuickBarTarget(state) }
    target.supportsLists = supportsLists
    target.usesMarkdownPreview = usesMarkdownPreview
    target.insertDl = insertDl
    target.selectionCommands = selectionCommands
    DisposableEffect(targets, target) {
        onDispose { targets.blur(target) }
    }
    return onFocusChanged { if (it.isFocused) targets.focus(target) else targets.blur(target) }
}
