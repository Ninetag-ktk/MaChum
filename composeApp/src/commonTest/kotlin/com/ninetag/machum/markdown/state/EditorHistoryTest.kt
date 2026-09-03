package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorHistoryTest {

    @Test
    fun typingInSameBlockWithinWindow_coalescesIntoOneUndoEntry() {
        val history = EditorHistory(snapshot("a"))

        history.record(snapshot("ab"), typing("text", 100))
        history.record(snapshot("abc"), typing("text", 700))

        assertEquals(1, history.undoSize)
        assertEquals("a", history.undo()?.markdown())
        assertEquals("abc", history.redo()?.markdown())
    }

    @Test
    fun typingOutsideWindowOrInDifferentBlock_startsNewTransaction() {
        val history = EditorHistory(snapshot("a"))

        history.record(snapshot("ab"), typing("first", 100))
        history.record(snapshot("abc"), typing("first", 851))
        history.record(snapshot("abcd"), typing("second", 900))

        assertEquals(3, history.undoSize)
        assertEquals("abc", history.undo()?.markdown())
        assertEquals("ab", history.undo()?.markdown())
        assertEquals("a", history.undo()?.markdown())
    }

    @Test
    fun atomicChangesNeverCoalesce() {
        val history = EditorHistory(snapshot("a"))

        history.record(snapshot("b"), EditorHistoryTransaction.Atomic)
        history.record(snapshot("c"), EditorHistoryTransaction.Atomic)

        assertEquals(2, history.undoSize)
        assertEquals("b", history.undo()?.markdown())
        assertEquals("a", history.undo()?.markdown())
    }

    @Test
    fun editingAfterUndo_discardsRedoBranch() {
        val history = EditorHistory(snapshot("a"))
        history.record(snapshot("b"), EditorHistoryTransaction.Atomic)
        history.record(snapshot("c"), EditorHistoryTransaction.Atomic)

        assertEquals("b", history.undo()?.markdown())
        assertTrue(history.canRedo)

        history.record(snapshot("d"), EditorHistoryTransaction.Atomic)

        assertFalse(history.canRedo)
        assertNull(history.redo())
        assertEquals("b", history.undo()?.markdown())
    }

    @Test
    fun resetEstablishesExternalBaselineAndClearsBothStacks() {
        val history = EditorHistory(snapshot("a"))
        history.record(snapshot("b"), EditorHistoryTransaction.Atomic)
        history.undo()

        history.reset(snapshot("external"))

        assertEquals("external", history.current.markdown())
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun undoStack_isBounded() {
        val history = EditorHistory(snapshot("0"), maxUndoEntries = 2)
        history.record(snapshot("1"), EditorHistoryTransaction.Atomic)
        history.record(snapshot("2"), EditorHistoryTransaction.Atomic)
        history.record(snapshot("3"), EditorHistoryTransaction.Atomic)

        assertEquals(2, history.undoSize)
        assertEquals("2", history.undo()?.markdown())
        assertEquals("1", history.undo()?.markdown())
        assertNull(history.undo())
    }

    @Test
    fun identicalSnapshot_isIgnoredWithoutDestroyingRedo() {
        val initial = snapshot("a")
        val history = EditorHistory(initial)
        history.record(snapshot("b"), EditorHistoryTransaction.Atomic)
        history.undo()
        assertTrue(history.canRedo)

        assertFalse(history.record(initial, EditorHistoryTransaction.Atomic))

        assertTrue(history.canRedo)
        assertEquals("b", history.redo()?.markdown())
    }

    @Test
    fun classifier_identifiesOneFieldTypingAcrossNestedBlocks() {
        val before = listOf(
            EditorBlock.Callout(
                id = "callout",
                calloutType = "NOTE",
                titleState = TextFieldState("title"),
                bodyBlocks = listOf(EditorBlock.Text("body", TextFieldState("a"))),
            ),
        ).toEditorBlockSnapshots()
        val after = listOf(
            EditorBlock.Callout(
                id = "callout",
                calloutType = "NOTE",
                titleState = TextFieldState("title"),
                bodyBlocks = listOf(EditorBlock.Text("body", TextFieldState("ab"))),
            ),
        ).toEditorBlockSnapshots()

        assertEquals(
            EditorHistoryTransaction.Typing("callout/body:text", 100),
            classifyEditorHistoryTransaction(before, after, 100),
        )
    }

    @Test
    fun classifier_treatsStructureMetadataAndMultipleFieldsAsAtomic() {
        val original = listOf(EditorBlock.Text("text", TextFieldState("a"))).toEditorBlockSnapshots()
        val rawChanged = listOf(
            EditorBlock.Text("text", TextFieldState("a"), rawMode = true, rawOrigin = RawOrigin.CODE),
        ).toEditorBlockSnapshots()
        assertEquals(
            EditorHistoryTransaction.Atomic,
            classifyEditorHistoryTransaction(original, rawChanged, 100),
        )

        val tableBefore = listOf(
            EditorBlock.Table(
                "table",
                listOf(TextFieldState("A"), TextFieldState("B")),
                listOf(listOf(TextFieldState("1"), TextFieldState("2"))),
            ),
        ).toEditorBlockSnapshots()
        val tableAfter = listOf(
            EditorBlock.Table(
                "table",
                listOf(TextFieldState("AA"), TextFieldState("B")),
                listOf(listOf(TextFieldState("1"), TextFieldState("22"))),
            ),
        ).toEditorBlockSnapshots()
        assertEquals(
            EditorHistoryTransaction.Atomic,
            classifyEditorHistoryTransaction(tableBefore, tableAfter, 100),
        )
    }

    @Test
    fun selectionReplacementAndBufferedTypingUndoWithoutCursorHistory() {
        val original = listOf(
            EditorBlock.Text("first", TextFieldState("alpha")),
            EditorBlock.Text("last", TextFieldState("omega")),
        )
        val history = EditorHistory(captureEditorDocumentSnapshot(original))
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "first", 2),
            focus = SelectionEndpoint(emptyList(), "last", 2),
        )
        val replacement = requireNotNull(replaceSelectedText(original, selection, "X"))
        history.record(
            captureEditorDocumentSnapshot(replacement.blocks),
            EditorHistoryTransaction.Atomic,
        )

        var focus = requireNotNull(continueTextInputAt(replacement.blocks, replacement.focus, "Y"))
        var nextSnapshot = captureEditorDocumentSnapshot(replacement.blocks)
        history.record(
            nextSnapshot,
            classifyEditorHistoryTransaction(history.current.blocks, nextSnapshot.blocks, 100),
        )
        focus = requireNotNull(continueTextInputAt(replacement.blocks, focus, "Z"))
        nextSnapshot = captureEditorDocumentSnapshot(replacement.blocks)
        history.record(
            nextSnapshot,
            classifyEditorHistoryTransaction(history.current.blocks, nextSnapshot.blocks, 200),
        )

        assertEquals(5, focus.offset)
        assertEquals("alXYZega", history.current.markdown())
        assertEquals("alXega", history.undo()?.markdown())
        assertEquals("alpha\nomega", history.undo()?.markdown())
    }

    private fun typing(blockId: String, time: Long) =
        EditorHistoryTransaction.Typing(blockId, time)

    private fun snapshot(markdown: String): EditorDocumentSnapshot = captureEditorDocumentSnapshot(
        blocks = listOf(EditorBlock.Text("text", TextFieldState(markdown))),
    )

    private fun EditorDocumentSnapshot.markdown(): String = restoreBlocks().toMarkdown()
}
