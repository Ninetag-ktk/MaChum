package com.ninetag.machum.external

import com.ninetag.machum.entity.PlotStage

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
    fun crlfFrontMatter_preservesLineEndingsUnknownKeysAndBody_whenMetadataChanges() {
        val raw = "---\r\nid: abc12345\r\ncustom: 값\r\ntags: [old]\r\n---\r\n\r\n첫 줄\r\n둘째 줄"

        val updated = NoteFile.parse(raw).withTags(listOf("old", "새 태그"))
        val injected = updated.inject()

        assertEquals("abc12345", updated.id)
        assertEquals("첫 줄\r\n둘째 줄", updated.body)
        assertTrue(injected.contains("custom: 값\r\n"))
        assertTrue(injected.contains("tags:\r\n  - old\r\n  - 새_태그"))
        assertEquals(injected, NoteFile.parse(injected).inject())
    }

    @Test
    fun utf8BomCrLfFrontMatter_isRecognizedWithoutCreatingSecondFrontMatter() {
        val raw = "\uFEFF---\r\ncustom: keep\r\n---\r\n\r\n본문"

        val indexed = NoteFile.parse(raw)
            .ensureId()
            .withTags(listOf("프로젝트 태그"))
        val injected = indexed.inject()
        val fenceLines = injected.removePrefix("\uFEFF").split("\r\n").count { it == "---" }

        assertTrue(injected.startsWith("\uFEFF---\r\n"))
        assertEquals(2, fenceLines)
        assertTrue(injected.contains("custom: keep\r\n"))
        assertEquals("본문", indexed.body)
        assertEquals("본문", NoteFile.parse(injected).body)
    }

    @Test
    fun utf8BomLfFrontMatter_roundTripsVerbatim() {
        val raw = "\uFEFF---\nid: abc12345\ncustom: keep\n---\n\n본문"

        assertEquals(raw, NoteFile.parse(raw).inject())
    }

    @Test
    fun utf8BomLfBodyWithoutFrontMatter_keepsBomAtAbsoluteStartWhenIndexed() {
        val raw = "\uFEFF# 제목\n본문"

        val indexed = NoteFile.parse(raw)
            .ensureId()
            .withTags(listOf("프로젝트 태그"))
        val injected = indexed.inject()

        assertTrue(injected.startsWith("\uFEFF---\n"))
        assertTrue(!injected.substring(1).contains('\uFEFF'))
        assertEquals("# 제목\n본문", indexed.body)
        assertEquals(indexed.body, NoteFile.parse(injected).body)
    }

    @Test
    fun utf8BomCrLfBodyWithoutFrontMatter_usesCrLfAndKeepsBomAtAbsoluteStartWhenIndexed() {
        val raw = "\uFEFF# 제목\r\n본문"

        val indexed = NoteFile.parse(raw)
            .ensureId()
            .withTags(listOf("프로젝트 태그"))
        val injected = indexed.inject()

        assertTrue(injected.startsWith("\uFEFF---\r\n"))
        assertTrue(!injected.substring(1).contains('\uFEFF'))
        assertTrue(injected.contains("tags:\r\n  - 프로젝트_태그\r\n---\r\n\r\n# 제목\r\n본문"))
        assertEquals("# 제목\r\n본문", NoteFile.parse(injected).body)
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
    fun withTags_replacesWhitespaceWithUnderscore_andDeduplicates() {
        val note = NoteFile.parse("본문").withTags(
            listOf("폴더 1", " 캐릭터 설정 ", "#폴더_1"),
        )

        assertEquals(listOf("폴더_1", "캐릭터_설정"), note.tags)
        assertTrue(note.inject().contains("tags:\n  - 폴더_1\n  - 캐릭터_설정"))
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
    fun plotStageUsesNumberParenthesisAndStageFrontmatterFormat() {
        val note = NoteFile.parse("본문").withPlotStage(PlotStage.SETUP)

        assertEquals(PlotStage.SETUP, note.plotStage)
        assertEquals("1) 발단", note.plot)
        assertEquals("1) 발단", NoteFile.PLOT_VALUES[1])
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
