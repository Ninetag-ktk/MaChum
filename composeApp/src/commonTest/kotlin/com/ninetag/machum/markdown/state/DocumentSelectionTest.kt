package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DocumentSelectionTest {

    @Test
    fun normalize_ordersReverseSelectionByDocumentPosition() {
        val blocks = listOf(text("a", "alpha"), text("b", "beta"))
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "b", 2),
            focus = SelectionEndpoint(emptyList(), "a", 1),
        )

        val normalized = assertNotNull(selection.normalize(blocks))
        assertEquals(SelectionEndpoint(emptyList(), "a", 1), normalized.start)
        assertEquals(SelectionEndpoint(emptyList(), "b", 2), normalized.end)
    }

    @Test
    fun normalize_returnsNullForStaleEndpoint() {
        val blocks = listOf(text("a", "alpha"))
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "missing", 0),
            focus = SelectionEndpoint(emptyList(), "a", 1),
        )

        assertNull(selection.normalize(blocks))
    }

    @Test
    fun extractMarkdown_includesPartialTextAndWholeAtomicBlock() {
        val blocks = listOf(
            text("a", "alpha"),
            EditorBlock.Code(id = "code", language = "", codeState = TextFieldState("x")),
            text("b", "omega"),
        )
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "a", 2),
            focus = SelectionEndpoint(emptyList(), "b", 2),
        )

        assertEquals("pha\n```\nx\n```\nom", extractMarkdown(blocks, selection))
    }

    @Test
    fun normalize_promotesNestedEndpointToParentCallout() {
        val callout = EditorBlock.Callout(
            id = "callout",
            calloutType = "NOTE",
            titleState = TextFieldState("title"),
            bodyBlocks = listOf(text("inner", "body")),
        )
        val blocks = listOf(text("outer", "alpha"), callout)
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "outer", 2),
            focus = SelectionEndpoint(listOf("callout"), "inner", 2),
        )

        val normalized = assertNotNull(selection.normalize(blocks))
        assertEquals("callout", normalized.end.blockId)
        assertEquals(emptyList(), normalized.end.containerPath)
        assertEquals("pha\n${callout.toMarkdown()}", extractMarkdown(blocks, selection))
    }

    @Test
    fun nextFocusEndpoint_movesInsideCurrentContainerOnly() {
        val first = text("first", "a")
        val second = text("second", "second")
        val callout = EditorBlock.Callout(
            id = "callout",
            calloutType = "NOTE",
            titleState = TextFieldState("title"),
            bodyBlocks = listOf(first, second),
        )
        val blocks = listOf(callout, text("outside", "outside"))

        assertEquals(
            SelectionEndpoint(listOf("callout"), "second", 6),
            nextFocusEndpoint(
                blocks,
                SelectionEndpoint(listOf("callout"), "first", 0),
                down = true,
            ),
        )
        assertNull(
            nextFocusEndpoint(
                blocks,
                SelectionEndpoint(listOf("callout"), "second", 6),
                down = true,
            ),
        )
    }

    private fun text(id: String, value: String): EditorBlock.Text =
        EditorBlock.Text(id = id, textFieldState = TextFieldState(value))
}
