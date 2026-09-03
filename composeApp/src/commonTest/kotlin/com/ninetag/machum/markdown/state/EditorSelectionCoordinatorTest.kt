package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorSelectionCoordinatorTest {

    @Test
    fun selectAll_usesDocumentEdgesAndTextLength() {
        val selection = EditorSelectionCoordinator.selectAll(
            listOf(text("first", "alpha"), text("last", "omega")),
        )

        assertEquals(SelectionEndpoint(emptyList(), "first", 0), selection?.anchor)
        assertEquals(SelectionEndpoint(emptyList(), "last", 5), selection?.focus)
        assertNull(EditorSelectionCoordinator.selectAll(emptyList()))
    }

    @Test
    fun extendFromBlock_startsTextSelectionInBothDirections() {
        val blocks = listOf(text("first", "one"), text("middle", "middle"), text("last", "last"))

        val previous = assertIs<SelectionAdjustment.Set>(
            EditorSelectionCoordinator.extendFromBlock(
                currentBlock = blocks[1],
                currentIndex = 1,
                blocksInContainer = blocks,
                containerPath = emptyList(),
                currentSelection = DocumentSelection.None,
                direction = SelectionDirection.Previous,
            ),
        ).selection
        assertEquals(SelectionEndpoint(emptyList(), "middle", 6), previous.anchor)
        assertEquals(SelectionEndpoint(emptyList(), "first", 0), previous.focus)

        val next = assertIs<SelectionAdjustment.Set>(
            EditorSelectionCoordinator.extendFromBlock(
                currentBlock = blocks[1],
                currentIndex = 1,
                blocksInContainer = blocks,
                containerPath = emptyList(),
                currentSelection = DocumentSelection.None,
                direction = SelectionDirection.Next,
            ),
        ).selection
        assertEquals(SelectionEndpoint(emptyList(), "middle", 0), next.anchor)
        assertEquals(SelectionEndpoint(emptyList(), "last", 4), next.focus)
    }

    @Test
    fun extendFromBlock_selectsAtomicNeighbourWithoutRetainingOldAnchor() {
        val text = text("text", "body")
        val code = EditorBlock.Code("code", "", TextFieldState("x"))
        val existing = DocumentSelection.Multi(
            SelectionEndpoint(emptyList(), "old", 1),
            SelectionEndpoint(emptyList(), "text", 2),
        )

        val selection = assertIs<SelectionAdjustment.Set>(
            EditorSelectionCoordinator.extendFromBlock(
                currentBlock = text,
                currentIndex = 0,
                blocksInContainer = listOf(text, code),
                containerPath = listOf("parent"),
                currentSelection = existing,
                direction = SelectionDirection.Next,
            ),
        ).selection

        assertEquals(SelectionEndpoint(listOf("parent"), "code", 0), selection.anchor)
        assertEquals(
            SelectionEndpoint(listOf("parent"), "code", SelectionEndpoint.ATOMIC_END),
            selection.focus,
        )
    }

    @Test
    fun extendFromBlock_distinguishesRootAndNestedContainerBoundaries() {
        val only = text("only", "value")

        assertIs<SelectionAdjustment.Keep>(
            EditorSelectionCoordinator.extendFromBlock(
                only, 0, listOf(only), emptyList(), DocumentSelection.None, SelectionDirection.Previous,
            ),
        )
        assertIs<SelectionAdjustment.EscapeToParent>(
            EditorSelectionCoordinator.extendFromBlock(
                only, 0, listOf(only), listOf("callout"), DocumentSelection.None, SelectionDirection.Previous,
            ),
        )
    }

    @Test
    fun extendExisting_movesFocusAndPreservesAnchor() {
        val blocks = listOf(text("first", "one"), text("second", "second"))
        val original = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "first", 1),
            focus = SelectionEndpoint(emptyList(), "first", 1),
        )

        val extended = EditorSelectionCoordinator.extendExisting(
            blocks,
            original,
            SelectionDirection.Next,
        )

        assertEquals(original.anchor, extended?.anchor)
        assertEquals(SelectionEndpoint(emptyList(), "second", 6), extended?.focus)
        assertNull(
            EditorSelectionCoordinator.extendExisting(
                blocks,
                assertIs<DocumentSelection.Multi>(extended),
                SelectionDirection.Next,
            ),
        )
    }

    @Test
    fun focusReset_preservesEndpointsAndClearsUnrelatedBlock() {
        val selection = DocumentSelection.Multi(
            SelectionEndpoint(emptyList(), "anchor", 0),
            SelectionEndpoint(emptyList(), "focus", 1),
        )

        assertFalse(EditorSelectionCoordinator.shouldClearOnFocus(selection, "anchor"))
        assertFalse(EditorSelectionCoordinator.shouldClearOnFocus(selection, "focus"))
        assertTrue(EditorSelectionCoordinator.shouldClearOnFocus(selection, "other"))
        assertFalse(EditorSelectionCoordinator.shouldClearOnFocus(DocumentSelection.None, "other"))
    }

    private fun text(id: String, value: String): EditorBlock.Text =
        EditorBlock.Text(id = id, textFieldState = TextFieldState(value))
}
