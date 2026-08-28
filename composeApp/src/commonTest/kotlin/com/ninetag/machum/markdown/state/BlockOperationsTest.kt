package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlockOperationsTest {

    @Test
    fun trySplitTextBlock_splitsOpeningCodeFenceAndTargetsNewBlock() {
        val source = EditorBlock.Text(
            id = "text",
            textFieldState = TextFieldState("before\n```kotlin"),
        )

        val result = assertNotNull(BlockOperations.trySplitTextBlock(listOf(source), 0))
        assertEquals(1, result.focusBlockIndex)
        assertEquals("before", assertIs<EditorBlock.Text>(result.newBlocks[0]).toMarkdown())
        assertEquals("kotlin", assertIs<EditorBlock.Code>(result.newBlocks[1]).language)
    }

    @Test
    fun trySplitTextBlock_splitsCalloutHeaderAndTargetsNewBlock() {
        val source = EditorBlock.Text(
            id = "text",
            textFieldState = TextFieldState("before\n> [!NOTE] title"),
        )

        val result = assertNotNull(BlockOperations.trySplitTextBlock(listOf(source), 0))
        val callout = assertIs<EditorBlock.Callout>(result.newBlocks[1])
        assertEquals(1, result.focusBlockIndex)
        assertEquals("NOTE", callout.calloutType)
        assertEquals("title", callout.titleState.text.toString())
    }

    @Test
    fun tryReparse_splitsMixedTextAndTargetsSpecialBlock() {
        val source = EditorBlock.Text(
            id = "text",
            textFieldState = TextFieldState("intro\n```kotlin\nx\n```"),
        )

        val result = assertNotNull(BlockOperations.tryReparse(listOf(source), 0))
        assertEquals(1, result.focusBlockIndex)
        assertIs<EditorBlock.Text>(result.newBlocks[0])
        assertIs<EditorBlock.Code>(result.newBlocks[1])
    }

    @Test
    fun dissolveAndReparse_withIntactMarker_restoresSpecialBlock() {
        val code = EditorBlock.Code(
            id = "code",
            language = "kotlin",
            codeState = TextFieldState("val x = 1"),
        )

        val dissolved = assertNotNull(BlockOperations.dissolveSpecial(listOf(code), 0))
        val raw = assertIs<EditorBlock.Text>(dissolved.newBlocks.single())
        assertEquals(RawOrigin.CODE, raw.rawOrigin)
        assertEquals(code.toMarkdown(), raw.textFieldState.text.toString())
        assertEquals(code.toMarkdown().length, dissolved.cursorOffset)

        val reparsed = assertNotNull(BlockOperations.tryReparse(dissolved.newBlocks, 0))
        assertIs<EditorBlock.Code>(reparsed.newBlocks.single())
    }

    @Test
    fun rawBlock_withBrokenMarker_becomesPlainText() {
        val raw = EditorBlock.Text(
            id = "raw",
            textFieldState = TextFieldState("```kotlin\nval x = 1"),
            rawMode = true,
            rawOrigin = RawOrigin.CODE,
        )

        val result = assertNotNull(BlockOperations.tryReparse(listOf(raw), 0))
        val plain = assertIs<EditorBlock.Text>(result.newBlocks.single())
        assertFalse(plain.rawMode)
        assertNull(plain.rawOrigin)
    }

    @Test
    fun mergeWithPrevious_preservesPreviousIdAndCursorOffset() {
        val previous = EditorBlock.Text(id = "previous", textFieldState = TextFieldState("abc"))
        val current = EditorBlock.Text(id = "current", textFieldState = TextFieldState("def"))

        val result = assertNotNull(BlockOperations.mergeWithPrevious(listOf(previous, current), 1))
        val merged = assertIs<EditorBlock.Text>(result.newBlocks.single())
        assertEquals("previous", merged.id)
        assertEquals("abc\ndef", merged.toMarkdown())
        assertEquals(3, result.focusCursorOffset)
    }
}
