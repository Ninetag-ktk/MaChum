package com.ninetag.machum.markdown

import com.ninetag.machum.markdown.parser.InlineParser
import com.ninetag.machum.markdown.token.InlineToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InlineParserTest {

    private val parser = InlineParser()

    @Test
    fun plainText() {
        val result = parser.parse("hello")
        assertEquals(listOf(InlineToken.Text("hello")), result)
    }

    @Test
    fun bold() {
        val result = parser.parse("**hi**")
        assertEquals(1, result.size)
        val bold = assertIs<InlineToken.Bold>(result[0])
        assertEquals(listOf(InlineToken.Text("hi")), bold.children)
    }

    @Test
    fun italic() {
        val result = parser.parse("*hi*")
        assertEquals(1, result.size)
        val italic = assertIs<InlineToken.Italic>(result[0])
        assertEquals(listOf(InlineToken.Text("hi")), italic.children)
    }

    @Test
    fun boldItalic() {
        val result = parser.parse("***hi***")
        assertEquals(1, result.size)
        val bold = assertIs<InlineToken.Bold>(result[0])
        assertEquals(1, bold.children.size)
        assertIs<InlineToken.Italic>(bold.children[0])
    }

    @Test
    fun strikethrough() {
        val result = parser.parse("~~hi~~")
        assertEquals(1, result.size)
        val s = assertIs<InlineToken.Strikethrough>(result[0])
        assertEquals(listOf(InlineToken.Text("hi")), s.children)
    }

    @Test
    fun highlight() {
        val result = parser.parse("==hi==")
        assertEquals(1, result.size)
        val h = assertIs<InlineToken.Highlight>(result[0])
        assertEquals(listOf(InlineToken.Text("hi")), h.children)
    }

    @Test
    fun inlineCode() {
        val result = parser.parse("`code`")
        assertEquals(listOf(InlineToken.InlineCode("code")), result)
    }

    @Test
    fun inlineCodeIgnoresInternalFormatting() {
        val result = parser.parse("`**no bold**`")
        assertEquals(listOf(InlineToken.InlineCode("**no bold**")), result)
    }

    @Test
    fun wikiLink() {
        val result = parser.parse("[[note]]")
        assertEquals(listOf(InlineToken.WikiLink(target = "note", alias = null)), result)
    }

    @Test
    fun wikiLinkWithAlias() {
        val result = parser.parse("[[note|alias]]")
        assertEquals(listOf(InlineToken.WikiLink(target = "note", alias = "alias")), result)
    }

    @Test
    fun externalLink() {
        val result = parser.parse("[text](https://example.com)")
        assertEquals(listOf(InlineToken.ExternalLink(text = "text", url = "https://example.com")), result)
    }

    @Test
    fun embedLink() {
        val result = parser.parse("![[file.md]]")
        assertEquals(listOf(InlineToken.EmbedLink(fileName = "file.md")), result)
    }

    @Test
    fun snakeCaseIsNotItalic() {
        val result = parser.parse("snake_case_word")
        assertEquals(listOf(InlineToken.Text("snake_case_word")), result)
    }

    @Test
    fun unpaired_asteriskIsPlainText() {
        val result = parser.parse("**unclosed")
        // Should not produce Bold — treat as plain text
        assert(result.none { it is InlineToken.Bold })
    }

    @Test
    fun nestedBoldItalic() {
        val result = parser.parse("**a *b* c**")
        assertEquals(1, result.size)
        val bold = assertIs<InlineToken.Bold>(result[0])
        assertEquals(3, bold.children.size)
        assertEquals(InlineToken.Text("a "), bold.children[0])
        assertIs<InlineToken.Italic>(bold.children[1])
        assertEquals(InlineToken.Text(" c"), bold.children[2])
    }

    @Test
    fun lineBreak() {
        val result = parser.parse("a\nb")
        assertEquals(3, result.size)
        assertEquals(InlineToken.Text("a"), result[0])
        assertEquals(InlineToken.LineBreak, result[1])
        assertEquals(InlineToken.Text("b"), result[2])
    }
}
