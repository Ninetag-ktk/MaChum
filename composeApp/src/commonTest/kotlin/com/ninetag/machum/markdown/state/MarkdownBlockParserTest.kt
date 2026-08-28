package com.ninetag.machum.markdown.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownBlockParserTest {

    @Test
    fun parseAndSerialize_preservesMixedDocument() {
        val markdown = """
            # 제목
            본문

            ```kotlin
            val answer = 42
            ```

            > [!NOTE] 안내
            > body

            | A | B |
            | --- | --- |
            | 1 | 2 |
        """.trimIndent()

        assertEquals(markdown, MarkdownBlockParser.parse(markdown).toMarkdown())
    }

    @Test
    fun parseAndSerialize_preservesBlankLinesBetweenAtomicBlocks() {
        val markdown = "```\na\n```\n\n```\nb\n```"
        val blocks = MarkdownBlockParser.parse(markdown)

        assertEquals(3, blocks.size)
        assertIs<EditorBlock.Code>(blocks[0])
        val blank = assertIs<EditorBlock.Text>(blocks[1])
        assertTrue(blank.textFieldState.text.contains(EditorBlock.BLANK_LINE_MARKER))
        assertIs<EditorBlock.Code>(blocks[2])
        assertEquals(markdown, blocks.toMarkdown())
    }

    @Test
    fun closedCodeFence_isCodeBlock() {
        val code = assertIs<EditorBlock.Code>(
            MarkdownBlockParser.parse("```kotlin\nval x = 1\n```").single(),
        )

        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.codeState.text.toString())
    }

    @Test
    fun unclosedCodeFence_staysText() {
        val markdown = "```kotlin\nval x = 1"
        val text = assertIs<EditorBlock.Text>(MarkdownBlockParser.parse(markdown).single())

        assertEquals(markdown, text.textFieldState.text.toString())
        assertEquals(markdown, text.toMarkdown())
    }

    @Test
    fun nestedCallout_isParsedRecursively() {
        val markdown = "> [!NOTE] Outer\n> > [!WARNING] Inner\n> > deep"
        val outer = assertIs<EditorBlock.Callout>(MarkdownBlockParser.parse(markdown).single())
        val inner = assertIs<EditorBlock.Callout>(outer.bodyBlocks.single())

        assertEquals("NOTE", outer.calloutType)
        assertEquals("WARNING", inner.calloutType)
        assertEquals(markdown, outer.toMarkdown())
    }

    @Test
    fun dialogueCallout_doesNotCreateNestedDialogueCallout() {
        val markdown = "> [!DL] Outer\n> > [!DL] Inner\n> > body"
        val outer = assertIs<EditorBlock.Callout>(MarkdownBlockParser.parse(markdown).single())

        assertEquals("DL", outer.calloutType)
        assertTrue(outer.bodyBlocks.all { it !is EditorBlock.Callout })
        assertEquals(markdown, outer.toMarkdown())
    }

    @Test
    fun oneLineTable_staysText() {
        val markdown = "| A | B |"
        assertIs<EditorBlock.Text>(MarkdownBlockParser.parse(markdown).single())
        assertEquals(markdown, MarkdownBlockParser.parse(markdown).toMarkdown())
    }

    @Test
    fun tableWithoutSeparator_staysText() {
        val markdown = "| A | B |\n| 1 | 2 |"
        assertIs<EditorBlock.Text>(MarkdownBlockParser.parse(markdown).single())
        assertEquals(markdown, MarkdownBlockParser.parse(markdown).toMarkdown())
    }

    @Test
    fun irregularTable_isPaddedToMaximumColumnCount() {
        val markdown = "| A | B |\n| --- | --- | --- |\n| 1 |\n| 2 | 3 | 4 |"
        val table = assertIs<EditorBlock.Table>(MarkdownBlockParser.parse(markdown).single())

        assertEquals(3, table.headerStates.size)
        assertEquals(listOf("A", "B", ""), table.headerStates.map { it.text.toString() })
        assertEquals(listOf(3, 3), table.rowStates.map { it.size })
        assertEquals(listOf("1", "", ""), table.rowStates[0].map { it.text.toString() })
        assertEquals(listOf("2", "3", "4"), table.rowStates[1].map { it.text.toString() })
        assertEquals(
            "| A | B |  |\n| --- | --- | --- |\n| 1 |  |  |\n| 2 | 3 | 4 |",
            table.toMarkdown(),
        )
    }
}
