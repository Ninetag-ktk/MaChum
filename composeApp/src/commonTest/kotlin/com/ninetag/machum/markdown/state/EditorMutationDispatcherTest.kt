package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class EditorMutationDispatcherTest {
    @Test
    fun mergeCarriesTargetAndExactCursorOffset() {
        val previous = EditorBlock.Text(id = "previous", textFieldState = TextFieldState("abc"))
        val current = EditorBlock.Text(id = "current", textFieldState = TextFieldState("def"))

        val mutation = assertNotNull(
            EditorMutationDispatcher.mergeWithPrevious(listOf(previous, current), 1)
        )

        assertEquals("abc\ndef", assertIs<EditorBlock.Text>(mutation.blocks.single()).toMarkdown())
        assertEquals("previous", mutation.focusIntent?.targetBlockId)
        assertEquals(CursorHint.AtOffset(3), mutation.focusIntent?.cursorHint)
    }

    @Test
    fun mergeFallbackDissolvesPreviousSpecialBlock() {
        val callout = EditorBlock.Callout(
            id = "callout",
            calloutType = "NOTE",
            titleState = TextFieldState("title"),
            bodyBlocks = emptyList(),
        )
        val current = EditorBlock.Text(id = "current", textFieldState = TextFieldState("body"))

        val mutation = assertNotNull(
            EditorMutationDispatcher.mergeWithPrevious(listOf(callout, current), 1)
        )
        val raw = assertIs<EditorBlock.Text>(mutation.blocks.first())

        assertEquals(RawOrigin.CALLOUT, raw.rawOrigin)
        assertEquals(raw.id, mutation.focusIntent?.targetBlockId)
        assertEquals(CursorHint.AtOffset(raw.textFieldState.text.length), mutation.focusIntent?.cursorHint)
    }

    @Test
    fun silentReparseChangesBlocksWithoutRequestingFocus() {
        val raw = EditorBlock.Text(
            id = "raw",
            textFieldState = TextFieldState("> [!NOTE] title"),
            rawMode = true,
            rawOrigin = RawOrigin.CALLOUT,
        )

        val mutation = assertNotNull(
            EditorMutationDispatcher.reparse(listOf(raw), 0, requestFocus = false)
        )

        assertIs<EditorBlock.Callout>(mutation.blocks.single())
        assertNull(mutation.focusIntent)
    }

    @Test
    fun clearRawModePreservesBlockIdentityAndTextState() {
        val state = TextFieldState("plain")
        val raw = EditorBlock.Text(
            id = "raw",
            textFieldState = state,
            rawMode = true,
            rawOrigin = RawOrigin.CODE,
        )

        val mutation = assertNotNull(EditorMutationDispatcher.clearRawMode(listOf(raw), 0))
        val cleared = assertIs<EditorBlock.Text>(mutation.blocks.single())

        assertEquals("raw", cleared.id)
        assertSame(state, cleared.textFieldState)
        assertFalse(cleared.rawMode)
        assertNull(cleared.rawOrigin)
        assertNull(mutation.focusIntent)
    }
}
