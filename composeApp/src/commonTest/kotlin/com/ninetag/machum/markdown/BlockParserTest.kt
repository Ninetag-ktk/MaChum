package com.ninetag.machum.markdown

import com.ninetag.machum.markdown.parser.MarkdownParserImpl
import com.ninetag.machum.markdown.token.InlineToken
import com.ninetag.machum.markdown.token.MarkdownBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlockParserTest {

    private val parser = MarkdownParserImpl()

    @Test
    fun headingLevel2() {
        val result = parser.parse("## Title")
        assertEquals(1, result.blocks.size)
        val heading = assertIs<MarkdownBlock.Heading>(result.blocks[0])
        assertEquals(2, heading.level)
        assertEquals(listOf(InlineToken.Text("Title")), heading.inlines)
    }

    @Test
    fun headingLevel1Through6() {
        for (level in 1..6) {
            val result = parser.parse("${"#".repeat(level)} H$level")
            val heading = assertIs<MarkdownBlock.Heading>(result.blocks[0])
            assertEquals(level, heading.level)
        }
    }

    @Test
    fun horizontalRule() {
        val result = parser.parse("---")
        assertEquals(1, result.blocks.size)
        assertIs<MarkdownBlock.HorizontalRule>(result.blocks[0])
    }

    @Test
    fun codeBlock() {
        val result = parser.parse("```kotlin\nval x = 1\n```")
        assertEquals(1, result.blocks.size)
        val code = assertIs<MarkdownBlock.CodeBlock>(result.blocks[0])
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.content)
    }

    @Test
    fun codeBlockNoLanguage() {
        val result = parser.parse("```\ncode\n```")
        val code = assertIs<MarkdownBlock.CodeBlock>(result.blocks[0])
        assertEquals("", code.language)
        assertEquals("code", code.content)
    }

    @Test
    fun callout() {
        val result = parser.parse("> [!NOTE] 제목\n> 본문")
        assertEquals(1, result.blocks.size)
        val callout = assertIs<MarkdownBlock.Callout>(result.blocks[0])
        assertEquals("NOTE", callout.type)
        assertEquals("제목", callout.title)
        assertEquals(1, callout.body.size)
        assertIs<MarkdownBlock.TextBlock>(callout.body[0])
    }

    @Test
    fun calloutCaseInsensitive() {
        val result = parser.parse("> [!warning] title")
        val callout = assertIs<MarkdownBlock.Callout>(result.blocks[0])
        assertEquals("warning", callout.type)
    }

    @Test
    fun blockquote() {
        val result = parser.parse("> some text")
        assertEquals(1, result.blocks.size)
        val bq = assertIs<MarkdownBlock.Blockquote>(result.blocks[0])
        assertEquals(1, bq.body.size)
        assertIs<MarkdownBlock.TextBlock>(bq.body[0])
    }

    @Test
    fun bulletListFlat() {
        val result = parser.parse("- a\n- b")
        assertEquals(1, result.blocks.size)
        val list = assertIs<MarkdownBlock.BulletList>(result.blocks[0])
        assertEquals(2, list.items.size)
    }

    @Test
    fun bulletListNested() {
        val result = parser.parse("- a\n  - b")
        assertEquals(1, result.blocks.size)
        val list = assertIs<MarkdownBlock.BulletList>(result.blocks[0])
        assertEquals(1, list.items.size)
        assertEquals(1, list.items[0].children.size)
        assertIs<MarkdownBlock.BulletList>(list.items[0].children[0])
    }

    @Test
    fun orderedList() {
        val result = parser.parse("1. first\n2. second")
        assertEquals(1, result.blocks.size)
        assertIs<MarkdownBlock.OrderedList>(result.blocks[0])
    }

    @Test
    fun tableWithSeparator() {
        val result = parser.parse("|A|B|\n|---|---|\n|1|2|")
        assertEquals(1, result.blocks.size)
        val table = assertIs<MarkdownBlock.Table>(result.blocks[0])
        assertEquals(2, table.headers.size)
        assertEquals(1, table.rows.size)
        assertEquals(2, table.rows[0].size)
    }

    @Test
    fun tableWithoutSeparatorFallsBackToText() {
        val result = parser.parse("|A|\n|B|")
        // BlockSplitter: second row is separate block (no lookahead match for non-separator)
        // or fallback to TextBlock — either way no Table
        assert(result.blocks.none { it is MarkdownBlock.Table })
    }

    @Test
    fun embedBasic() {
        val result = parser.parse("![[note.md]]")
        assertEquals(1, result.blocks.size)
        val embed = assertIs<MarkdownBlock.Embed>(result.blocks[0])
        assertEquals("note.md", embed.fileName)
        assertNull(embed.section)
        assertNull(embed.blockId)
    }

    @Test
    fun embedWithSection() {
        val result = parser.parse("![[note.md#heading]]")
        val embed = assertIs<MarkdownBlock.Embed>(result.blocks[0])
        assertEquals("note.md", embed.fileName)
        assertEquals("heading", embed.section)
        assertNull(embed.blockId)
    }

    @Test
    fun embedWithBlockId() {
        val result = parser.parse("![[note.md^myid]]")
        val embed = assertIs<MarkdownBlock.Embed>(result.blocks[0])
        assertEquals("note.md", embed.fileName)
        assertNull(embed.section)
        assertEquals("myid", embed.blockId)
    }

    @Test
    fun embedContentResolved() {
        val embedContents = mapOf("note.md" to "# Embedded Heading")
        val result = parser.parse("![[note.md]]", embedContents)
        val embed = assertIs<MarkdownBlock.Embed>(result.blocks[0])
        assertNotNull(embed.content)
        assertIs<MarkdownBlock.Heading>(embed.content)
    }

    @Test
    fun lineToBlockIndexMapping() {
        val text = "# Heading\n\nparagraph\n\n- item"
        val result = parser.parse(text)
        assertEquals(3, result.blocks.size)
        // line 0 → block 0 (Heading)
        assertEquals(0, result.lineToBlockIndex[0])
        // line 2 → block 1 (paragraph)
        assertEquals(1, result.lineToBlockIndex[2])
        // line 4 → block 2 (list)
        assertEquals(2, result.lineToBlockIndex[4])
    }

    @Test
    fun textBlock() {
        val result = parser.parse("hello world")
        assertEquals(1, result.blocks.size)
        assertIs<MarkdownBlock.TextBlock>(result.blocks[0])
    }

    @Test
    fun multipleBlocks() {
        val result = parser.parse("# H1\n\nparagraph\n\n```\ncode\n```")
        assertEquals(3, result.blocks.size)
        assertIs<MarkdownBlock.Heading>(result.blocks[0])
        assertIs<MarkdownBlock.TextBlock>(result.blocks[1])
        assertIs<MarkdownBlock.CodeBlock>(result.blocks[2])
    }
}
