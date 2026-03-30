package com.ninetag.machum.markdown.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpanManagerTest {

    // ── shiftSpans ────────────────────────────────────────────────────────────

    @Test
    fun `shift zero delta returns same spans`() {
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 4))
        assertEquals(spans, SpanManager.shiftSpans(spans, 0, 0))
    }

    @Test
    fun `insert before span shifts both start and end`() {
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 5, 9))
        val shifted = SpanManager.shiftSpans(spans, 0, 3)  // insert 3 chars at pos 0
        assertEquals(listOf(MarkupStyleRange(MarkupStyle.Bold, 8, 12)), shifted)
    }

    @Test
    fun `insert inside span extends end only`() {
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 5))
        val shifted = SpanManager.shiftSpans(spans, 3, 2)  // insert 2 chars at pos 3
        assertEquals(listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 7)), shifted)
    }

    @Test
    fun `insert at span end extends span`() {
        // changeStart == span.end: span.end 위치에서의 삽입은 스팬을 확장
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 4))
        val shifted = SpanManager.shiftSpans(spans, 4, 3)  // insert AT end
        assertEquals(listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 7)), shifted)
    }

    @Test
    fun `insert strictly after span does not change span`() {
        // changeStart > span.end: 스팬 뒤에서의 삽입은 스팬 불변
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 4))
        val shifted = SpanManager.shiftSpans(spans, 5, 3)  // insert strictly after end
        assertEquals(spans, shifted)
    }

    @Test
    fun `delete inside span shrinks end`() {
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 8))
        val shifted = SpanManager.shiftSpans(spans, 2, -2)  // delete 2 chars at pos 2
        assertEquals(listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 6)), shifted)
    }

    @Test
    fun `delete before span shifts span left`() {
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 5, 9))
        val shifted = SpanManager.shiftSpans(spans, 0, -3)
        assertEquals(listOf(MarkupStyleRange(MarkupStyle.Bold, 2, 6)), shifted)
    }

    @Test
    fun `delete that collapses span removes it`() {
        val spans = listOf(MarkupStyleRange(MarkupStyle.Bold, 2, 4))
        val shifted = SpanManager.shiftSpans(spans, 0, -10)
        assertTrue(shifted.isEmpty())
    }

    // ── consolidate ───────────────────────────────────────────────────────────

    @Test
    fun `consolidate adjacent same-style spans`() {
        val spans = listOf(
            MarkupStyleRange(MarkupStyle.Bold, 0, 4),
            MarkupStyleRange(MarkupStyle.Bold, 4, 8),
        )
        val result = SpanManager.consolidate(spans)
        assertEquals(1, result.size)
        assertEquals(MarkupStyleRange(MarkupStyle.Bold, 0, 8), result[0])
    }

    @Test
    fun `consolidate overlapping same-style spans`() {
        val spans = listOf(
            MarkupStyleRange(MarkupStyle.Italic, 0, 5),
            MarkupStyleRange(MarkupStyle.Italic, 3, 8),
        )
        val result = SpanManager.consolidate(spans)
        assertEquals(1, result.size)
        assertEquals(MarkupStyleRange(MarkupStyle.Italic, 0, 8), result[0])
    }

    @Test
    fun `consolidate non-adjacent same-style spans kept separate`() {
        val spans = listOf(
            MarkupStyleRange(MarkupStyle.Bold, 0, 3),
            MarkupStyleRange(MarkupStyle.Bold, 5, 8),
        )
        val result = SpanManager.consolidate(spans)
        assertEquals(2, result.size)
    }

    @Test
    fun `consolidate different styles kept separate`() {
        val spans = listOf(
            MarkupStyleRange(MarkupStyle.Bold, 0, 4),
            MarkupStyleRange(MarkupStyle.Italic, 0, 4),
        )
        val result = SpanManager.consolidate(spans)
        assertEquals(2, result.size)
    }

    // ── mergeSpans ────────────────────────────────────────────────────────────

    @Test
    fun `merge updates overlapping heading span`() {
        val existing = listOf(
            MarkupStyleRange(MarkupStyle.H1, 0, 7),
            MarkupStyleRange(MarkupStyle.Bold, 0, 4),
        )
        val markdown = listOf(MarkupStyleRange(MarkupStyle.H1, 0, 10))
        val result = SpanManager.mergeSpans(existing, markdown)
        // Overlapping H1 in existing is replaced by the new H1(0,10)
        assertTrue(result.any { it.style == MarkupStyle.H1 && it.end == 10 })
        assertEquals(1, result.count { it.style == MarkupStyle.H1 })
        // Bold span from existing is kept (not replaced by markdown)
        assertTrue(result.any { it.style == MarkupStyle.Bold })
    }

    @Test
    fun `merge preserves heading on other line when new heading on different line`() {
        // Line 1: H1 span (0..7), Line 2: new Bold span detected, no new heading
        val existing = listOf(MarkupStyleRange(MarkupStyle.H1, 0, 7))
        val markdown  = listOf(MarkupStyleRange(MarkupStyle.Bold, 9, 13))
        val result = SpanManager.mergeSpans(existing, markdown)
        // H1 from line 1 must be preserved
        assertTrue(result.any { it.style == MarkupStyle.H1 && it.start == 0 && it.end == 7 })
        assertTrue(result.any { it.style == MarkupStyle.Bold })
    }

    @Test
    fun `merge replaces duplicate inline spans`() {
        val existing = listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 4))
        val markdown  = listOf(MarkupStyleRange(MarkupStyle.Bold, 0, 4))
        val result = SpanManager.mergeSpans(existing, markdown)
        // Should not have duplicates
        assertEquals(1, result.count { it.style == MarkupStyle.Bold && it.start == 0 && it.end == 4 })
    }

    // ── resolveChangeOrigin ───────────────────────────────────────────────────

    @Test
    fun `resolve origin for insertion`() {
        // cursor at 5 after inserting 2 chars → origin = 5 - 2 = 3
        val origin = SpanManager.resolveChangeOrigin(5, 2, 10)
        assertEquals(3, origin)
    }

    @Test
    fun `resolve origin for deletion`() {
        // cursor at 3 after deleting 2 chars → origin = 3
        val origin = SpanManager.resolveChangeOrigin(3, -2, 10)
        assertEquals(3, origin)
    }
}
