package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.EditorHistory
import com.ninetag.machum.markdown.state.EditorHistoryTransaction
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.SelectionEndpoint
import com.ninetag.machum.markdown.state.captureEditorDocumentSnapshot
import com.ninetag.machum.markdown.state.normalizeForContainer
import com.ninetag.machum.markdown.state.restoreBlocks
import com.ninetag.machum.markdown.state.toMarkdown
import com.ninetag.machum.markdown.ui.selection.isBlockInSelection
import androidx.compose.foundation.text.input.TextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownBlockTextFieldTest {
    @Test
    fun emptyMarkdownCreatesOneEditableTextBlockWithoutChangingSerializedValue() {
        val blocks = parseEditorDocument("")

        assertEquals(1, blocks.size)
        assertIs<EditorBlock.Text>(blocks.single())
        assertEquals("", blocks.toMarkdown())
    }

    @Test
    fun nonEmptyMarkdownKeepsParserResult() {
        val blocks = parseEditorDocument("문장")

        assertEquals(1, blocks.size)
        assertEquals("문장", blocks.toMarkdown())
    }

    @Test
    fun nestedCalloutSelectionUsesAbsolutePathAndHighlightsLocalBodyRange() {
        val body = listOf(
            EditorBlock.Text("first", TextFieldState("alpha")),
            EditorBlock.Text("second", TextFieldState("omega")),
        )
        val selection = DocumentSelection.Multi(
            anchor = SelectionEndpoint(listOf("outer"), "first", 2),
            focus = SelectionEndpoint(listOf("outer"), "second", 3),
        )

        val normalized = assertNotNull(
            selection.normalizeForContainer(body, containerPath = listOf("outer")),
        )

        assertTrue(isBlockInSelection(0, body, listOf("outer"), normalized))
        assertTrue(isBlockInSelection(1, body, listOf("outer"), normalized))
        assertEquals(listOf("outer"), normalized.start.containerPath)
        assertEquals(listOf("outer"), normalized.end.containerPath)
    }

    @Test
    fun trailingInputAfterCalloutCodeAndTableIsVirtualUntilFirstCommittedEditAndUndoable() {
        val endings = listOf(
            "Callout" to EditorBlock.Callout(
                id = "callout",
                calloutType = "NOTE",
                titleState = TextFieldState("title"),
                bodyBlocks = listOf(EditorBlock.Text("body", TextFieldState("body"))),
            ),
            "Code" to EditorBlock.Code(
                id = "code",
                language = "kotlin",
                codeState = TextFieldState("val answer = 42"),
            ),
            "Table" to EditorBlock.Table(
                id = "table",
                headerStates = listOf(TextFieldState("A"), TextFieldState("B")),
                rowStates = listOf(listOf(TextFieldState("1"), TextFieldState("2"))),
            ),
        )

        endings.forEach { (name, ending) ->
            val blocks = listOf(ending)
            val canonicalBefore = blocks.toMarkdown()
            val history = EditorHistory(captureEditorDocumentSnapshot(blocks))

            assertTrue(shouldShowTrailingTextInput(blocks), "$name should show trailing input")
            assertEquals(
                canonicalBefore,
                blocks.toMarkdown(),
                "$name affordance must not change serialization",
            )
            assertFalse(
                shouldMaterializeTrailingTextInput("가", hasComposition = true),
                "$name must not materialize during IME composition",
            )

            val trailing = EditorBlock.Text("$name-tail", TextFieldState("가"))
            assertTrue(shouldMaterializeTrailingTextInput("가", hasComposition = false))
            val materialized = assertNotNull(materializeTrailingTextInput(blocks, trailing))
            assertEquals("가", (materialized.last() as EditorBlock.Text).textFieldState.text.toString())
            assertEquals("$canonicalBefore\n가", materialized.toMarkdown())

            history.record(
                snapshot = captureEditorDocumentSnapshot(materialized),
                transaction = EditorHistoryTransaction.Atomic,
            )
            assertEquals(canonicalBefore, history.undo()?.restoreBlocks()?.toMarkdown())
        }
    }

    @Test
    fun trailingInputIsNotDuplicatedForTextEmptyOrNestedEditors() {
        val textEnding = listOf(EditorBlock.Text("text", TextFieldState("body")))
        val codeEnding = listOf(EditorBlock.Code("code", "", TextFieldState("body")))

        assertFalse(shouldShowTrailingTextInput(emptyList()))
        assertFalse(shouldShowTrailingTextInput(textEnding))
        assertFalse(shouldShowTrailingTextInput(codeEnding, isNested = true))
    }

    @Test
    fun onlySoleEmptyTopLevelTextUsesTheWholeViewport() {
        val emptyText = EditorBlock.Text("empty", TextFieldState(""))
        val contentText = EditorBlock.Text("content", TextFieldState("content"))
        val rawEmptyText = EditorBlock.Text(
            id = "raw-empty",
            textFieldState = TextFieldState(""),
            rawMode = true,
        )

        assertTrue(shouldExpandEmptyRootInput(listOf(emptyText)))
        assertFalse(shouldExpandEmptyRootInput(listOf(contentText)))
        assertFalse(shouldExpandEmptyRootInput(listOf(emptyText), isNested = true))
        assertFalse(shouldExpandEmptyRootInput(listOf(emptyText, contentText)))
        assertFalse(shouldExpandEmptyRootInput(listOf(rawEmptyText)))
    }

    @Test
    fun trailingInputConsumesOnlyTheRemainingViewportHeight() {
        assertEquals(380, remainingTrailingInputHeightPx(600, 220))
        assertEquals(0, remainingTrailingInputHeightPx(600, 600))
        assertEquals(0, remainingTrailingInputHeightPx(600, 720))
    }
}
