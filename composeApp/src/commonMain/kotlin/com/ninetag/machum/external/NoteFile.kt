package com.ninetag.machum.external

import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.normalizeTags

/**
 * 마크다운 노트 = YAML 프론트매터 + 본문.
 *
 * ## 프론트매터 정책 (docs/product-roadmap.md)
 *
 * - **관리 키**(`id`/`tags`/`aliases`/`plot`)만 구조적으로 읽고 병합·쓴다.
 * - 앱이 기록하는 `tags` 항목의 공백은 `_`로 정규화한다.
 * - 그 외 키/주석/키 순서/포맷은 **원형 그대로 보존**한다 (Obsidian 호환의 핵심 계약).
 * - 관리 키는 **실제로 수정될 때만** 표준 형태로 정규화된다:
 *   스칼라 = `key: value`, 리스트 = block `- ` 형태. 수정 전에는 원본 그대로 유지되므로
 *   순수 `parse → inject` 왕복은 (본문 앞 공백 정규화를 제외하면) 무손실이다.
 *
 * 프론트매터를 top-level 키 단위 `Block` 리스트로 관리한다. YAML 라이브러리를 쓰지 않는 이유는
 * round-trip 시 라이브러리가 따옴표/키 순서/주석을 임의로 재포맷해 원형 보존 계약을 깨기 때문이다.
 */
class NoteFile private constructor(
    private val blocks: List<Block>,
    val body: String,
    private val lineEnding: String = "\n",
    private val hasUtf8Bom: Boolean = false,
) {
    // --- 관리 키 접근자 ---

    /** 프론트매터 `id` (커밋 정체성용). 없으면 null. */
    val id: String? get() = scalarOf(KEY_ID)

    /** 프론트매터 `plot` 선택 필드. 미설정이면 null. */
    val plot: String? get() = scalarOf(KEY_PLOT)

    /** 알려진 고정 단계로 해석된 `plot`. 알 수 없거나 미설정이면 null. */
    val plotStage: PlotStage? get() = PlotStage.fromFrontmatter(plot)

    /** 프론트매터 `tags`. 없으면 빈 리스트. */
    val tags: List<String> get() = listValueOf(KEY_TAGS)

    /** 프론트매터 `aliases`. 없으면 빈 리스트. */
    val aliases: List<String> get() = listValueOf(KEY_ALIASES)

    // --- 관리 키 세터 (수정 시 표준 형태로 정규화) ---

    fun withId(id: String): NoteFile = withScalar(KEY_ID, id)
    fun withPlot(plot: String?): NoteFile = withScalar(KEY_PLOT, plot)
    fun withPlotStage(plotStage: PlotStage?): NoteFile = withPlot(plotStage?.frontmatterValue)
    fun withTags(tags: List<String>): NoteFile = withList(KEY_TAGS, normalizeTags(tags))
    fun withAliases(aliases: List<String>): NoteFile = withList(KEY_ALIASES, aliases)

    // --- 기존 API 호환 ---

    fun ensureId(): NoteFile = if (id != null) this else withId(generatedId())

    /** 본문만 교체하고 프론트매터(관리/미관리 모두)는 보존한다. id 가 없으면 생성한다. */
    fun withBody(body: String): NoteFile = NoteFile(blocks, body, lineEnding, hasUtf8Bom).ensureId()

    /** 프론트매터 + 본문을 raw markdown 문자열로 직렬화. 같은 내용이면 항상 같은 문자열(diff 안정성). */
    fun inject(): String {
        val bom = if (hasUtf8Bom) UTF8_BOM.toString() else ""
        if (blocks.isEmpty()) return bom + body
        val fm = blocks.mapNotNull { it.render(lineEnding) }.joinToString(lineEnding)
        return "$bom---$lineEnding$fm$lineEnding---$lineEnding$lineEnding$body"
    }

    // --- 내부 조회/수정 헬퍼 ---

    private fun scalarOf(key: String): String? =
        blocks.filterIsInstance<Block.Scalar>().lastOrNull { it.key == key }?.value

    private fun listValueOf(key: String): List<String> =
        blocks.filterIsInstance<Block.ListBlock>().lastOrNull { it.key == key }?.items ?: emptyList()

    private fun withScalar(key: String, value: String?): NoteFile {
        val replacement = Block.Scalar(key, original = null, value = value, dirty = true)
        return NoteFile(replaceOrAppend(key, replacement), body, lineEnding, hasUtf8Bom)
    }

    private fun withList(key: String, items: List<String>): NoteFile {
        val replacement = Block.ListBlock(key, original = null, items = items, dirty = true)
        return NoteFile(replaceOrAppend(key, replacement), body, lineEnding, hasUtf8Bom)
    }

    /** 같은 키 블록이 있으면 위치 유지하며 교체, 없으면 끝에 추가. */
    private fun replaceOrAppend(key: String, replacement: Block): List<Block> {
        val idx = blocks.indexOfLast { it.keyOrNull() == key }
        return if (idx >= 0) blocks.toMutableList().apply { set(idx, replacement) }
        else blocks + replacement
    }

    /** 프론트매터 top-level 항목. 관리 키는 구조적으로, 나머지는 원형(raw)으로 보존. */
    private sealed class Block {
        /** 직렬화 결과. null 이면 출력하지 않음(관리 키 삭제). */
        abstract fun render(lineEnding: String): String?

        abstract fun keyOrNull(): String?

        /** 미관리 키 / 주석 / preamble — 원형 그대로 보존. */
        class Raw(val text: String) : Block() {
            override fun render(lineEnding: String): String = text
            override fun keyOrNull(): String? = null
        }

        /** 관리 스칼라 (id, plot). */
        class Scalar(
            val key: String,
            private val original: String?,
            val value: String?,
            private val dirty: Boolean,
        ) : Block() {
            override fun render(lineEnding: String): String? = when {
                !dirty -> original
                value == null -> null
                else -> "$key: $value"
            }
            override fun keyOrNull(): String = key
        }

        /** 관리 리스트 (tags, aliases). 수정 시 block `- ` 형태로 정규화. */
        class ListBlock(
            val key: String,
            private val original: String?,
            val items: List<String>,
            private val dirty: Boolean,
        ) : Block() {
            override fun render(lineEnding: String): String? = when {
                !dirty -> original
                items.isEmpty() -> null
                else -> buildString {
                    append(key).append(":")
                    items.forEach { append(lineEnding).append("  - ").append(it) }
                }
            }
            override fun keyOrNull(): String = key
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TAGS = "tags"
        const val KEY_ALIASES = "aliases"
        const val KEY_PLOT = "plot"

        /** 앱 전역 plot 선택 값. 기존 호출부 호환을 위해 문자열 목록도 제공한다. */
        val PLOT_VALUES = PlotStage.entries.map(PlotStage::frontmatterValue)

        private val CHARS = ('a'..'z') + ('0'..'9')
        private val MANAGED_KEYS = setOf(KEY_ID, KEY_TAGS, KEY_ALIASES, KEY_PLOT)
        private const val UTF8_BOM = '\uFEFF'

        fun parse(raw: String): NoteFile {
            val hasUtf8Bom = raw.startsWith(UTF8_BOM)
            val content = if (hasUtf8Bom) raw.drop(1) else raw
            val fallbackLineEnding = detectLineEnding(content)
            val lineEnding = when {
                content.startsWith("---\r\n") -> "\r\n"
                content.startsWith("---\n") -> "\n"
                else -> return NoteFile(emptyList(), content, fallbackLineEnding, hasUtf8Bom)
            }
            val frontMatterStart = 3 + lineEnding.length
            val closeMarker = findClosingFence(content, frontMatterStart, lineEnding)
                ?: return NoteFile(emptyList(), content, fallbackLineEnding, hasUtf8Bom)

            val frontMatter = content.substring(frontMatterStart, closeMarker)
            val fenceEnd = closeMarker + lineEnding.length + 3
            var bodyStart = fenceEnd
            if (content.startsWith(lineEnding, bodyStart)) {
                bodyStart += lineEnding.length
                // 표준 frontmatter와 본문 사이의 빈 줄 하나만 분리한다. 본문 내부 줄바꿈은 그대로 둔다.
                if (content.startsWith(lineEnding, bodyStart)) {
                    bodyStart += lineEnding.length
                }
            }
            val body = content.substring(bodyStart)

            return NoteFile(
                blocks = buildBlocks(frontMatter, lineEnding),
                body = body,
                lineEnding = lineEnding,
                hasUtf8Bom = hasUtf8Bom,
            )
        }

        private fun generatedId(): String = (1..8).map { CHARS.random() }.joinToString("")

        private fun detectLineEnding(content: String): String =
            if ("\r\n" in content) "\r\n" else "\n"

        /** 프론트매터 텍스트를 top-level 키 단위 블록으로 분해. */
        private fun buildBlocks(frontMatter: String, lineEnding: String): List<Block> {
            val blocks = mutableListOf<Block>()
            var curKey: String? = null
            val cur = mutableListOf<String>()

            fun flush() {
                if (cur.isEmpty()) return
                blocks += makeBlock(curKey, cur.toList(), lineEnding)
                cur.clear()
                curKey = null
            }

            for (line in frontMatter.split(lineEnding)) {
                val key = topLevelKey(line)
                if (key != null) {
                    flush()
                    curKey = key
                }
                cur += line
            }
            flush()
            return blocks
        }

        /** 줄 전체가 `---`인 첫 닫는 fence 앞의 줄바꿈 위치를 반환한다. */
        private fun findClosingFence(content: String, startIndex: Int, lineEnding: String): Int? {
            val marker = "$lineEnding---"
            var markerIndex = content.indexOf(marker, startIndex = startIndex)
            while (markerIndex >= 0) {
                val afterFence = markerIndex + marker.length
                if (afterFence == content.length || content.startsWith(lineEnding, afterFence)) {
                    return markerIndex
                }
                markerIndex = content.indexOf(marker, startIndex = markerIndex + marker.length)
            }
            return null
        }

        private fun makeBlock(key: String?, lines: List<String>, lineEnding: String): Block {
            val raw = lines.joinToString(lineEnding)
            return when (key) {
                KEY_ID, KEY_PLOT -> Block.Scalar(key, original = raw, value = parseScalar(lines.first()), dirty = false)
                KEY_TAGS, KEY_ALIASES -> Block.ListBlock(key, original = raw, items = parseList(lines), dirty = false)
                else -> Block.Raw(raw)
            }
        }

        /**
         * top-level YAML 키 감지. 키면 키 이름, 아니면(들여쓰기/리스트항목/주석/빈줄/오탐) null.
         * `URL: http://x` 처럼 값 안의 `:` 는 첫 `:` 뒤 공백 규칙으로 걸러낸다.
         */
        private fun topLevelKey(line: String): String? {
            if (line.isEmpty()) return null
            val first = line[0]
            if (first.isWhitespace() || first == '#' || first == '-') return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val after = line.substring(colon + 1)
            if (after.isNotEmpty() && !after.startsWith(" ")) return null
            val key = line.substring(0, colon).trim()
            return key.takeIf { it in MANAGED_KEYS || it.isNotEmpty() }
        }

        private fun parseScalar(keyLine: String): String? =
            stripQuotes(keyLine.substringAfter(':', "").trim()).ifEmpty { null }

        private fun parseList(lines: List<String>): List<String> {
            val inline = lines.first().substringAfter(':', "").trim()
            return when {
                // block 형태:  key: \n   - a \n   - b
                inline.isEmpty() -> lines.drop(1).mapNotNull { line ->
                    val t = line.trim()
                    if (t.startsWith("-")) stripQuotes(t.removePrefix("-").trim()).ifEmpty { null } else null
                }
                // flow 형태:  key: [a, b]
                inline.startsWith("[") -> inline.removePrefix("[").removeSuffix("]")
                    .split(",").map { stripQuotes(it.trim()) }.filter { it.isNotEmpty() }
                // 인라인 CSV/단일 스칼라:  key: a, b  또는  key: a
                else -> inline.split(",").map { stripQuotes(it.trim()) }.filter { it.isNotEmpty() }
            }
        }

        private fun stripQuotes(s: String): String {
            if (s.length >= 2) {
                val f = s.first()
                val l = s.last()
                if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) return s.substring(1, s.length - 1)
            }
            return s
        }
    }
}
