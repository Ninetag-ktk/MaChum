package com.ninetag.machum.markdown

import com.ninetag.machum.markdown.parser.BlockSplitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlockSplitterTest {

    private val splitter = BlockSplitter()

    @Test
    fun emptyLinesSplitsTwoBlocks() {
        val result = splitter.split("a\n\nb")
        assertEquals(2, result.size)
        assertEquals(listOf("a"), result[0].lines)
        assertEquals(listOf("b"), result[1].lines)
    }

    @Test
    fun codeBlockPreservesInternalEmptyLine() {
        // "```", "a", "", "b", "```" = 5 lines
        val input = "```\na\n\nb\n```"
        val result = splitter.split(input)
        assertEquals(1, result.size)
        assertEquals(5, result[0].lines.size)
    }

    @Test
    fun blockIdAttachedToBlock() {
        val input = "para\n^abc"
        val result = splitter.split(input)
        assertEquals(1, result.size)
        assertEquals("abc", result[0].blockId)
    }

    @Test
    fun blockIdNotPresentWhenMissing() {
        val result = splitter.split("para")
        assertEquals(1, result.size)
        assertNull(result[0].blockId)
    }

    @Test
    fun quoteMultilineGroupedAsOneBlock() {
        val result = splitter.split("> a\n> b")
        assertEquals(1, result.size)
        assertEquals(2, result[0].lines.size)
    }

    @Test
    fun listWithIndentGroupedAsOneBlock() {
        val result = splitter.split("- a\n  - b")
        assertEquals(1, result.size)
        assertEquals(2, result[0].lines.size)
    }

    @Test
    fun tableWithSeparatorAsOneBlock() {
        val result = splitter.split("|A|\n|---|\n|1|")
        assertEquals(1, result.size)
        assertEquals(3, result[0].lines.size)
    }

    @Test
    fun embedSingleLine() {
        val result = splitter.split("![[note.md]]")
        assertEquals(1, result.size)
        assertEquals("![[note.md]]", result[0].lines.first())
    }

    @Test
    fun headingIsSingleBlock() {
        val result = splitter.split("# Title")
        assertEquals(1, result.size)
    }

    @Test
    fun headingDoesNotMergeWithNextParagraph() {
        val result = splitter.split("# Title\nparagraph")
        // Heading splits immediately, then paragraph is a new block
        assertEquals(2, result.size)
        assertEquals("# Title", result[0].lines.first())
    }

    @Test
    fun startLineTracking() {
        val result = splitter.split("a\n\nb")
        assertEquals(0, result[0].startLine)
        assertEquals(2, result[1].startLine)
    }

    @Test
    fun unclosedCodeBlockCollectedAsOneBlock() {
        val result = splitter.split("```\nfoo")
        assertEquals(1, result.size)
        assertEquals(2, result[0].lines.size)
    }
}
