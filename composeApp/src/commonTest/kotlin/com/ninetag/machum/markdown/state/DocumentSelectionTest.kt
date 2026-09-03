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

    @Test
    fun replaceSelectedMarkdown_deletesAcrossTextAndAtomicBlocks() {
        val blocks = listOf(
            text("a", "alpha"),
            EditorBlock.Code(id = "code", language = "", codeState = TextFieldState("x")),
            text("b", "omega"),
        )
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "a", 2),
            focus = SelectionEndpoint(emptyList(), "b", 2),
        )

        val replaced = assertNotNull(replaceSelectedMarkdown(blocks, selection, ""))

        assertEquals("alega", replaced.toMarkdown())
    }

    @Test
    fun replaceSelectedMarkdown_parsesPastedMarkdownAndPreservesUnaffectedIds() {
        val before = text("before", "before")
        val selected = text("selected", "selected")
        val after = text("after", "after")
        val blocks = listOf(before, selected, after)
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "selected", 0),
            focus = SelectionEndpoint(emptyList(), "selected", selected.textFieldState.text.length),
        )

        val replaced = assertNotNull(replaceSelectedMarkdown(blocks, selection, "```text\npasted\n```"))

        assertEquals("before", replaced.first().id)
        assertEquals("after", replaced.last().id)
        assertEquals("before\n```text\npasted\n```\nafter", replaced.toMarkdown())
    }

    @Test
    fun replaceSelectedMarkdown_updatesOnlyNestedCalloutBody() {
        val callout = EditorBlock.Callout(
            id = "callout",
            calloutType = "NOTE",
            titleState = TextFieldState("title"),
            bodyBlocks = listOf(text("first", "alpha"), text("second", "two")),
        )
        val outside = text("outside", "outside")
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(listOf("callout"), "first", 1),
            focus = SelectionEndpoint(listOf("callout"), "second", 1),
        )

        val replaced = assertNotNull(replaceSelectedMarkdown(listOf(callout, outside), selection, ""))
        val replacedCallout = replaced.first() as EditorBlock.Callout

        assertEquals("callout", replacedCallout.id)
        assertEquals("awo", replacedCallout.bodyBlocks.toMarkdown())
        assertEquals("outside", replaced.last().id)
    }

    @Test
    fun replaceSelectedMarkdown_leavesEditableTextWhenRootBecomesEmpty() {
        val only = text("only", "value")
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "only", 0),
            focus = SelectionEndpoint(emptyList(), "only", only.textFieldState.text.length),
        )

        val replaced = assertNotNull(replaceSelectedMarkdown(listOf(only), selection, ""))

        assertEquals(1, replaced.size)
        assertEquals("", replaced.toMarkdown())
        assertEquals("", (replaced.single() as EditorBlock.Text).textFieldState.text.toString())
    }

    @Test
    fun replaceSelectedText_joinsTextEndpointsAndPlacesCursorAfterInput() {
        val before = text("before", "before")
        val first = text("first", "alpha")
        val last = text("last", "omega")
        val after = text("after", "after")
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "first", 2),
            focus = SelectionEndpoint(emptyList(), "last", 2),
        )

        val result = assertNotNull(
            replaceSelectedText(listOf(before, first, last, after), selection, "한글"),
        )

        assertEquals("before\nal한글ega\nafter", result.blocks.toMarkdown())
        assertEquals("first", result.focus.blockId)
        assertEquals(4, result.focus.offset)
        assertEquals(4, (result.blocks[1] as EditorBlock.Text).textFieldState.selection.start)
        assertEquals("before", result.blocks.first().id)
        assertEquals("after", result.blocks.last().id)
    }

    @Test
    fun replaceSelectedText_replacesAtomicRangeWithEditableText() {
        val blocks = listOf(
            EditorBlock.Code(id = "code", language = "", codeState = TextFieldState("x")),
            EditorBlock.HorizontalRule(id = "rule"),
        )
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "code", SelectionEndpoint.ATOMIC_START),
            focus = SelectionEndpoint(emptyList(), "rule", SelectionEndpoint.ATOMIC_END),
        )

        val result = assertNotNull(replaceSelectedText(blocks, selection, "replacement"))

        assertEquals("replacement", result.blocks.toMarkdown())
        assertEquals(result.blocks.single().id, result.focus.blockId)
        assertEquals("replacement".length, result.focus.offset)
    }

    @Test
    fun replaceSelectedText_updatesNestedContainerAndKeepsParentId() {
        val callout = EditorBlock.Callout(
            id = "callout",
            calloutType = "NOTE",
            titleState = TextFieldState("title"),
            bodyBlocks = listOf(text("first", "alpha"), text("last", "omega")),
        )
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(listOf("callout"), "first", 1),
            focus = SelectionEndpoint(listOf("callout"), "last", 4),
        )

        val result = assertNotNull(replaceSelectedText(listOf(callout), selection, "X"))
        val updatedCallout = result.blocks.single() as EditorBlock.Callout

        assertEquals("callout", updatedCallout.id)
        assertEquals("aXa", updatedCallout.bodyBlocks.toMarkdown())
        assertEquals(listOf("callout"), result.focus.containerPath)
        assertEquals(2, result.focus.offset)
    }

    @Test
    fun replaceSelectedText_rejectsLineBreaksHandledByStructuralEditing() {
        val only = text("only", "value")
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(emptyList(), "only", 0),
            focus = SelectionEndpoint(emptyList(), "only", only.textFieldState.text.length),
        )

        assertNull(replaceSelectedText(listOf(only), selection, "line1\nline2"))
    }

    @Test
    fun continueTextInputAt_keepsFastBufferedInputInNestedReplacement() {
        val callout = EditorBlock.Callout(
            id = "callout",
            calloutType = "NOTE",
            titleState = TextFieldState("title"),
            bodyBlocks = listOf(text("first", "alpha"), text("last", "omega")),
        )
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(listOf("callout"), "first", 2),
            focus = SelectionEndpoint(listOf("callout"), "last", 2),
        )
        val replacement = assertNotNull(replaceSelectedText(listOf(callout), selection, "X"))

        val afterSecondCommit = assertNotNull(
            continueTextInputAt(replacement.blocks, replacement.focus, "Y"),
        )
        val afterThirdCommit = assertNotNull(
            continueTextInputAt(replacement.blocks, afterSecondCommit, "Z"),
        )
        val updatedBody = (replacement.blocks.single() as EditorBlock.Callout).bodyBlocks
        val targetState = (updatedBody.single() as EditorBlock.Text).textFieldState

        assertEquals("alXYZega", targetState.text.toString())
        assertEquals(5, afterThirdCommit.offset)
        assertEquals(5, targetState.selection.start)
        assertEquals(5, targetState.selection.end)
    }

    private fun text(id: String, value: String): EditorBlock.Text =
        EditorBlock.Text(id = id, textFieldState = TextFieldState(value))
}
