package com.ninetag.machum.markdown.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownSerializerTest {

    private fun serialize(text: String, vararg spans: MarkupStyleRange) =
        MarkdownSerializer.toMarkdown(text, spans.toList())

    // ── 스팬 없음 ──────────────────────────────────────────────────────────────

    @Test
    fun `no spans returns clean text unchanged`() {
        assertEquals("plain text", serialize("plain text"))
    }

    // ── 인라인 스타일 ──────────────────────────────────────────────────────────

    @Test
    fun `bold span serialized`() {
        assertEquals("**bold**", serialize("bold", MarkupStyleRange(MarkupStyle.Bold, 0, 4)))
    }

    @Test
    fun `italic span serialized`() {
        assertEquals("*italic*", serialize("italic", MarkupStyleRange(MarkupStyle.Italic, 0, 6)))
    }

    @Test
    fun `strikethrough span serialized`() {
        assertEquals(
            "~~strike~~",
            serialize("strike", MarkupStyleRange(MarkupStyle.Strikethrough, 0, 6)),
        )
    }

    @Test
    fun `highlight span serialized`() {
        assertEquals("==hi==", serialize("hi", MarkupStyleRange(MarkupStyle.Highlight, 0, 2)))
    }

    @Test
    fun `inline code span serialized`() {
        assertEquals("`code`", serialize("code", MarkupStyleRange(MarkupStyle.InlineCode, 0, 4)))
    }

    @Test
    fun `bold at non-zero offset`() {
        assertEquals(
            "prefix **bold** suffix",
            serialize(
                "prefix bold suffix",
                MarkupStyleRange(MarkupStyle.Bold, 7, 11),
            ),
        )
    }

    @Test
    fun `two non-overlapping inline spans`() {
        assertEquals(
            "**bold** and ~~strike~~",
            serialize(
                "bold and strike",
                MarkupStyleRange(MarkupStyle.Bold, 0, 4),
                MarkupStyleRange(MarkupStyle.Strikethrough, 9, 15),
            ),
        )
    }

    // ── Heading ────────────────────────────────────────────────────────────────

    @Test
    fun `h1 span serialized`() {
        assertEquals("# Heading", serialize("Heading", MarkupStyleRange(MarkupStyle.H1, 0, 7)))
    }

    @Test
    fun `h2 span serialized`() {
        assertEquals("## Title", serialize("Title", MarkupStyleRange(MarkupStyle.H2, 0, 5)))
    }

    @Test
    fun `h6 span serialized`() {
        assertEquals("###### Tiny", serialize("Tiny", MarkupStyleRange(MarkupStyle.H6, 0, 4)))
    }

    // ── 블록 (prefix 이미 존재) ───────────────────────────────────────────────

    @Test
    fun `bullet list prefix not duplicated`() {
        assertEquals(
            "- item",
            serialize("- item", MarkupStyleRange(MarkupStyle.BulletList, 0, 6)),
        )
    }

    @Test
    fun `blockquote prefix not duplicated`() {
        assertEquals(
            "> quote",
            serialize("> quote", MarkupStyleRange(MarkupStyle.Blockquote, 0, 7)),
        )
    }

    // ── 링크 (구분자 이미 존재) ───────────────────────────────────────────────

    @Test
    fun `wiki link delimiters not duplicated`() {
        assertEquals(
            "[[target]]",
            serialize("[[target]]", MarkupStyleRange(MarkupStyle.WikiLink, 0, 10)),
        )
    }

    // ── 라운드트립 ─────────────────────────────────────────────────────────────

    @Test
    fun `roundtrip bold`() {
        val original = "**bold**"
        val result = MarkdownStyleProcessor.process(original, original.length)!!
        val serialized = MarkdownSerializer.toMarkdown(result.cleanText, result.spans)
        assertEquals(original, serialized)
    }

    @Test
    fun `roundtrip italic`() {
        val original = "*italic*"
        val result = MarkdownStyleProcessor.process(original, original.length)!!
        assertEquals(original, MarkdownSerializer.toMarkdown(result.cleanText, result.spans))
    }

    @Test
    fun `roundtrip h1`() {
        // 커서를 줄 밖으로 설정해야 스트리핑이 발생함 → roundtrip 검증
        val original = "# Heading\n"
        val result = MarkdownStyleProcessor.process(original, original.length)!!
        assertEquals(original, MarkdownSerializer.toMarkdown(result.cleanText, result.spans))
    }

    @Test
    fun `roundtrip two inline styles`() {
        val original = "**bold** and ~~strike~~"
        val result = MarkdownStyleProcessor.process(original, original.length)!!
        assertEquals(original, MarkdownSerializer.toMarkdown(result.cleanText, result.spans))
    }

    @Test
    fun `roundtrip bullet list`() {
        val original = "- item"
        val result = MarkdownStyleProcessor.process(original, original.length)!!
        assertEquals(original, MarkdownSerializer.toMarkdown(result.cleanText, result.spans))
    }

    @Test
    fun `roundtrip wiki link`() {
        val original = "[[target]]"
        val result = MarkdownStyleProcessor.process(original, original.length)!!
        assertEquals(original, MarkdownSerializer.toMarkdown(result.cleanText, result.spans))
    }
}
