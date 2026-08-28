package com.ninetag.machum.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteFileTest {

    // --- 프론트매터 없음 ---

    @Test
    fun parse_noFrontMatter_bodyIsRaw_and_injectReturnsBody() {
        val raw = "# 제목\n본문 내용"
        val note = NoteFile.parse(raw)
        assertNull(note.id)
        assertEquals(raw, note.body)
        assertEquals(raw, note.inject())
        assertTrue(note.tags.isEmpty())
        assertTrue(note.aliases.isEmpty())
        assertNull(note.plot)
    }

    @Test
    fun ensureId_addsFrontMatter_whenNone() {
        val note = NoteFile.parse("본문").ensureId()
        assertNotNull(note.id)
        assertEquals(8, note.id!!.length)
        assertEquals("---\nid: ${note.id}\n---\n\n본문", note.inject())
    }

    @Test
    fun ensureId_isNoOp_whenIdExists() {
        val note = NoteFile.parse("---\nid: abc12345\n---\n\n본문")
        assertEquals("abc12345", note.id)
        assertEquals(note.inject(), note.ensureId().inject())
    }

    // --- 관리 키 읽기 ---

    @Test
    fun reads_all_managed_keys() {
        val raw = """
            ---
            id: a1b2c3d4
            tags:
              - 당신을_구하던_삶
              - 장면구상
            aliases:
              - 풀네임
            plot: 1) 발단
            ---

            본문
        """.trimIndent()
        val note = NoteFile.parse(raw)
        assertEquals("a1b2c3d4", note.id)
        assertEquals(listOf("당신을_구하던_삶", "장면구상"), note.tags)
        assertEquals(listOf("풀네임"), note.aliases)
        assertEquals("1) 발단", note.plot)
        assertEquals("본문", note.body)
    }

    @Test
    fun reads_flow_form_tags() {
        val note = NoteFile.parse("---\ntags: [캐릭터, 장면구상]\n---\n\n본문")
        assertEquals(listOf("캐릭터", "장면구상"), note.tags)
    }

    @Test
    fun reads_inline_csv_and_single_scalar_tags() {
        assertEquals(listOf("a", "b"), NoteFile.parse("---\ntags: a, b\n---\n\nx").tags)
        assertEquals(listOf("solo"), NoteFile.parse("---\ntags: solo\n---\n\nx").tags)
    }

    // --- 원형 보존 (미수정 시 verbatim) ---

    @Test
    fun roundTrip_preservesUnknownKeys_verbatim() {
        val raw = "---\ncssclasses:\n  - wide\ncustom: 값\nid: abc12345\ntags: [x, y]\n---\n\n본문"
        val note = NoteFile.parse(raw)
        // 미수정 → 완전 무손실 왕복
        assertEquals(raw, note.inject())
    }

    @Test
    fun inject_isStable_acrossReparse() {
        val raw = "---\nid: abc12345\nfoo: bar\ntags:\n  - a\n---\n\n본문"
        val once = NoteFile.parse(raw).inject()
        val twice = NoteFile.parse(once).inject()
        assertEquals(once, twice)
    }

    // --- 관리 키 수정 (정규화 + 나머지 보존) ---

    @Test
    fun withTags_normalizesToBlockForm_preservesOtherKeys() {
        val note = NoteFile.parse("---\nid: abc12345\ntags: [old]\ncustom: 값\n---\n\n본문")
            .withTags(listOf("새태그1", "새태그2"))
        val out = note.inject()
        assertEquals(listOf("새태그1", "새태그2"), NoteFile.parse(out).tags)
        // 다른 키 원형 보존
        assertTrue(out.contains("id: abc12345"))
        assertTrue(out.contains("custom: 값"))
        // block 형태로 정규화
        assertTrue(out.contains("tags:\n  - 새태그1\n  - 새태그2"))
    }

    @Test
    fun withPlot_addsWhenMissing_and_removesWhenNull() {
        val added = NoteFile.parse("---\nid: abc12345\n---\n\n본문").withPlot("2) 전개")
        assertEquals("2) 전개", added.plot)

        val removed = added.withPlot(null)
        assertNull(removed.plot)
        assertTrue(!removed.inject().contains("plot"))
    }

    @Test
    fun withAliases_appendsKeyWhenMissing() {
        val note = NoteFile.parse("---\nid: abc12345\n---\n\n본문").withAliases(listOf("모닥별"))
        assertEquals(listOf("모닥별"), note.aliases)
        assertEquals("abc12345", note.id)
    }

    // --- withBody ---

    @Test
    fun withBody_replacesBody_keepsFrontMatter() {
        val note = NoteFile.parse("---\nid: abc12345\ntags: [x]\n---\n\n옛 본문").withBody("새 본문")
        assertEquals("새 본문", note.body)
        assertEquals("abc12345", note.id)
        assertEquals(listOf("x"), note.tags)
    }

    @Test
    fun withBody_ensuresId_whenNoFrontMatter() {
        val note = NoteFile.parse("본문").withBody("새 본문")
        assertNotNull(note.id)
        assertTrue(note.inject().startsWith("---\nid: "))
        assertTrue(note.inject().endsWith("새 본문"))
    }
}
