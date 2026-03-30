package com.ninetag.machum.markdown.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MarkdownStyleProcessorTest {

    private fun process(text: String, cursor: Int = text.length) =
        MarkdownStyleProcessor.process(text, cursor)

    // ── 패턴 없음 ──────────────────────────────────────────────────────────────

    @Test
    fun `no patterns returns null`() {
        assertNull(process("plain text"))
    }

    @Test
    fun `incomplete bold returns null`() {
        assertNull(process("**incomplete"))
    }

    // ── 인라인 스타일 ──────────────────────────────────────────────────────────

    @Test
    fun `bold pattern stripped`() {
        val result = process("**bold**")
        assertNotNull(result)
        assertEquals("bold", result.cleanText)
        assertEquals(1, result.spans.size)
        assertEquals(MarkupStyle.Bold, result.spans[0].style)
        assertEquals(0, result.spans[0].start)
        assertEquals(4, result.spans[0].end)
    }

    @Test
    fun `italic star pattern stripped`() {
        val result = process("*italic*")
        assertNotNull(result)
        assertEquals("italic", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.Italic }
        assertEquals(0, span.start)
        assertEquals(6, span.end)
    }

    @Test
    fun `italic underscore pattern stripped`() {
        val result = process("_italic_")
        assertNotNull(result)
        assertEquals("italic", result.cleanText)
    }

    @Test
    fun `strikethrough pattern stripped`() {
        val result = process("~~strike~~")
        assertNotNull(result)
        assertEquals("strike", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.Strikethrough }
        assertEquals(0, span.start)
        assertEquals(6, span.end)
    }

    @Test
    fun `highlight pattern stripped`() {
        val result = process("==hi==")
        assertNotNull(result)
        assertEquals("hi", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.Highlight }
        assertEquals(0, span.start)
        assertEquals(2, span.end)
    }

    @Test
    fun `inline code pattern stripped`() {
        val result = process("`code`")
        assertNotNull(result)
        assertEquals("code", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.InlineCode }
        assertEquals(0, span.start)
        assertEquals(4, span.end)
    }

    @Test
    fun `two inline patterns on same line`() {
        val result = process("**bold** and ~~strike~~")
        assertNotNull(result)
        assertEquals("bold and strike", result.cleanText)
        val boldSpan   = result.spans.first { it.style == MarkupStyle.Bold }
        val strikeSpan = result.spans.first { it.style == MarkupStyle.Strikethrough }
        assertEquals(0, boldSpan.start);   assertEquals(4, boldSpan.end)
        assertEquals(9, strikeSpan.start); assertEquals(15, strikeSpan.end)
    }

    @Test
    fun `bold at non-zero offset`() {
        val result = process("prefix **bold** suffix")
        assertNotNull(result)
        assertEquals("prefix bold suffix", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.Bold }
        assertEquals(7, span.start)
        assertEquals(11, span.end)
    }

    // ── Heading ────────────────────────────────────────────────────────────────

    @Test
    fun `h1 prefix stripped when cursor leaves line`() {
        // 커서가 줄을 벗어나면(다음 줄로 이동) 헤딩 prefix 가 제거된다
        val result = process("# Heading\n", cursor = 10)
        assertNotNull(result)
        assertEquals("Heading\n", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.H1 }
        assertEquals(0, span.start)
        assertEquals(7, span.end)
    }

    @Test
    fun `h1 not stripped when cursor on heading line`() {
        // 커서가 헤딩 줄 위에 있으면 스트리핑 건너뜀 (Obsidian 동작, IME 보호)
        assertNull(process("# Heading"))
    }

    @Test
    fun `h2 prefix stripped when cursor leaves line`() {
        val result = process("## Title\n", cursor = 9)
        assertNotNull(result)
        assertEquals("Title\n", result.cleanText)
        assertEquals(MarkupStyle.H2, result.spans.first().style)
    }

    @Test
    fun `h6 prefix stripped when cursor leaves line`() {
        val result = process("###### Tiny\n", cursor = 12)
        assertNotNull(result)
        assertEquals("Tiny\n", result.cleanText)
        assertEquals(MarkupStyle.H6, result.spans.first().style)
    }

    @Test
    fun `multiline with h1 and plain`() {
        val result = process("# Title\nplain")
        assertNotNull(result)
        assertEquals("Title\nplain", result.cleanText)
        val h1 = result.spans.first { it.style == MarkupStyle.H1 }
        assertEquals(0, h1.start)
        assertEquals(5, h1.end)
    }

    // ── 블록 (노-스트립) ────────────────────────────────────────────────────────

    @Test
    fun `bullet list span applied`() {
        val result = process("- item")
        assertNotNull(result)
        assertEquals("- item", result.cleanText)  // prefix 유지
        val span = result.spans.first { it.style == MarkupStyle.BulletList }
        assertEquals(0, span.start)
        assertEquals(6, span.end)
    }

    @Test
    fun `ordered list span applied`() {
        val result = process("1. item")
        assertNotNull(result)
        assertEquals("1. item", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.OrderedList }
        assertEquals(0, span.start)
    }

    @Test
    fun `blockquote span applied`() {
        val result = process("> quote")
        assertNotNull(result)
        assertEquals("> quote", result.cleanText)
        val span = result.spans.first { it.style == MarkupStyle.Blockquote }
        assertEquals(0, span.start)
    }

    // ── 링크 ──────────────────────────────────────────────────────────────────

    @Test
    fun `wiki link span applied`() {
        val result = process("[[target]]")
        assertNotNull(result)
        assertEquals("[[target]]", result.cleanText)  // 구분자 유지
        val span = result.spans.first { it.style == MarkupStyle.WikiLink }
        assertEquals(0, span.start)
        assertEquals(10, span.end)
    }

    @Test
    fun `wiki link with alias span applied`() {
        val result = process("[[target|alias]]")
        assertNotNull(result)
        assertEquals(MarkupStyle.WikiLink, result.spans.first().style)
    }

    @Test
    fun `external link span applied`() {
        val result = process("[text](https://example.com)")
        assertNotNull(result)
        assertEquals(MarkupStyle.ExternalLink, result.spans.first().style)
    }

    // ── 커서 위치 보정 ─────────────────────────────────────────────────────────

    @Test
    fun `cursor after bold text adjusted correctly`() {
        // "**bold**" cursor=8(end) → cleanText="bold" cursor=4
        val result = process("**bold**", cursor = 8)
        assertNotNull(result)
        assertEquals(4, result.cursorPosition)
    }

    @Test
    fun `cursor before bold not moved`() {
        // cursor=0 (before **) → stays at 0
        val result = process("**bold**", cursor = 0)
        assertNotNull(result)
        assertEquals(0, result.cursorPosition)
    }

    @Test
    fun `cursor inside bold skips stripping`() {
        // cursor=1 (구분자 ** 내부) → IME 보호를 위해 스트리핑 건너뜀
        assertNull(process("**bold**", cursor = 1))
    }

    @Test
    fun `cursor after h1 prefix adjusted`() {
        // 커서가 다음 줄(10)에 있을 때 헤딩 prefix 제거, 커서 보정
        val result = process("# Heading\n", cursor = 10)
        assertNotNull(result)
        assertEquals(8, result.cursorPosition)  // 10 - prefixLen(2) = 8
    }
}
