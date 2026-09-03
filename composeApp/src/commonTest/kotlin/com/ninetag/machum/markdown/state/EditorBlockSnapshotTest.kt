package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class EditorBlockSnapshotTest {

    @Test
    fun roundTrip_preservesEveryBlockTypeAndNestedCallout() {
        val nestedText = EditorBlock.Text(
            id = "nested-text",
            textFieldState = TextFieldState("raw nested"),
            rawMode = true,
            rawOrigin = RawOrigin.CALLOUT,
        )
        val blocks = listOf(
            EditorBlock.Text("text", TextFieldState("alpha\n")),
            EditorBlock.Callout(
                id = "callout",
                calloutType = "NOTE",
                titleState = TextFieldState("title"),
                bodyBlocks = listOf(
                    nestedText,
                    EditorBlock.Callout(
                        id = "nested-callout",
                        calloutType = "TIP",
                        titleState = TextFieldState("nested title"),
                        bodyBlocks = listOf(EditorBlock.Code("nested-code", "kotlin", TextFieldState("val x = 1"))),
                    ),
                ),
            ),
            EditorBlock.Code("code", "json", TextFieldState("{\"a\":1}")),
            EditorBlock.Table(
                id = "table",
                headerStates = listOf(TextFieldState("A"), TextFieldState("B")),
                rowStates = listOf(
                    listOf(TextFieldState("1"), TextFieldState("2")),
                    listOf(TextFieldState("3"), TextFieldState("4")),
                ),
            ),
            EditorBlock.HorizontalRule("rule"),
            EditorBlock.Embed("embed", "Character/Hero"),
        )

        val snapshots = blocks.toEditorBlockSnapshots()
        val restored = snapshots.toEditorBlocks()

        assertEquals(snapshots, restored.toEditorBlockSnapshots())
        assertEquals(blocks.toMarkdown(), restored.toMarkdown())
        assertNotSame((blocks[0] as EditorBlock.Text).textFieldState, (restored[0] as EditorBlock.Text).textFieldState)
        val restoredCallout = restored[1] as EditorBlock.Callout
        assertNotSame(nestedText.textFieldState, (restoredCallout.bodyBlocks[0] as EditorBlock.Text).textFieldState)
    }

    @Test
    fun snapshot_isIndependentFromLaterTextFieldEdits() {
        val state = TextFieldState("before")
        val snapshot = listOf(EditorBlock.Text("text", state)).toEditorBlockSnapshots()

        state.edit { replace(0, length, "after") }

        assertEquals("before", (snapshot.single() as EditorBlockSnapshot.Text).text)
        assertEquals("before", snapshot.toEditorBlocks().single().toMarkdown())
    }

    @Test
    fun emptyDocumentAndEmptyTable_roundTripWithoutInventingContent() {
        assertEquals(emptyList(), emptyList<EditorBlock>().toEditorBlockSnapshots().toEditorBlocks())

        val table = EditorBlock.Table(
            id = "empty-table",
            headerStates = emptyList(),
            rowStates = emptyList(),
        )
        assertEquals(
            listOf(table).toEditorBlockSnapshots(),
            listOf(table).toEditorBlockSnapshots().toEditorBlocks().toEditorBlockSnapshots(),
        )
    }

    @Test
    fun documentSnapshot_restoresOnlyDocumentContent() {
        val snapshot = captureEditorDocumentSnapshot(
            blocks = listOf(EditorBlock.Text("text", TextFieldState("value"))),
        )

        assertEquals(snapshot.blocks, snapshot.restoreBlocks().toEditorBlockSnapshots())
    }
}
