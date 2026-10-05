package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState

/** Insert a new DL at the caret, moving selected text into its body when present. */
internal fun insertDlCallout(blocks: List<EditorBlock>, index: Int): EditorMutation? {
    val source = blocks.getOrNull(index) as? EditorBlock.Text ?: return null
    val state = source.textFieldState
    if (state.composition != null || source.rawMode) return null
    val text = state.text.toString()
    val start = minOf(state.selection.start, state.selection.end)
    val end = maxOf(state.selection.start, state.selection.end)
    val selectedText = text.substring(start, end)
    val callout = EditorBlock.Callout(
        calloutType = "DL",
        titleState = TextFieldState(""),
        bodyBlocks = if (start == end) emptyList() else listOf(
            EditorBlock.Text(textFieldState = TextFieldState(selectedText)),
        ),
    )
    val replacement = buildList {
        if (start > 0) add(source.copy(textFieldState = TextFieldState(text.substring(0, start))))
        add(callout)
        if (end < text.length) add(EditorBlock.Text(textFieldState = TextFieldState(text.substring(end))))
    }
    return EditorMutation(
        blocks = blocks.take(index) + replacement + blocks.drop(index + 1),
        focusIntent = if (selectedText.isEmpty()) {
            // An empty DL has no body entry, so begin in its title.
            EditorFocusIntent(callout.id, CursorHint.Start)
        } else {
            EditorFocusIntent(callout.id, CursorHint.CalloutBodyEnd)
        },
    )
}
