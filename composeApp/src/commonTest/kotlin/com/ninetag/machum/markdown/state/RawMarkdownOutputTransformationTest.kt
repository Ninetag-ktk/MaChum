package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawMarkdownOutputTransformationTest {
    private val config = MarkdownStyleConfig()

    @Test
    fun focusedLineKeepsSyntaxRawButAppliesInlineContentStyles() {
        val contentStyles = listOf(
            config.bold,
            config.italic,
            config.strikethrough,
            config.codeInline,
            config.link,
            config.h1,
        )
        val syntaxStyles = listOf(
            config.marker,
            config.blockTransparent,
            config.bulletPrefix,
            config.orderedPrefix,
        )

        contentStyles.forEach { style ->
            assertEquals(
                MarkdownSpanApplication.Everywhere,
                markdownSpanApplication(style, config, isFocused = true, isRawMode = false),
            )
        }
        syntaxStyles.forEach { style ->
            assertEquals(
                MarkdownSpanApplication.OutsideRawZones,
                markdownSpanApplication(style, config, isFocused = true, isRawMode = false),
            )
        }
    }

    @Test
    fun rawModeSuppressesAllStylesAndUnfocusedModePreviewsAllStyles() {
        val styles = listOf(
            config.marker,
            config.bold,
            config.italic,
            config.strikethrough,
            config.codeInline,
        )

        styles.forEach { style ->
            assertEquals(
                MarkdownSpanApplication.OutsideRawZones,
                markdownSpanApplication(style, config, isFocused = true, isRawMode = true),
            )
            assertEquals(
                MarkdownSpanApplication.Everywhere,
                markdownSpanApplication(style, config, isFocused = false, isRawMode = false),
            )
        }
    }

    @Test
    fun scannerFindsBoldItalicStrikeAndInlineCodeContentRanges() {
        val markdown = "**bold** *italic* ~~strike~~ `code`"
        val spans = MarkdownPatternScanner.scan(markdown, config).spans

        fun assertContentStyle(content: String, expectedStyle: androidx.compose.ui.text.SpanStyle) {
            val start = markdown.indexOf(content)
            val expectedRange = start until (start + content.length)
            assertTrue(
                spans.any { (range, style) -> range == expectedRange && style === expectedStyle },
                "Missing style for $content at $expectedRange",
            )
        }

        assertContentStyle("bold", config.bold)
        assertContentStyle("italic", config.italic)
        assertContentStyle("strike", config.strikethrough)
        assertContentStyle("code", config.codeInline)
    }
}
