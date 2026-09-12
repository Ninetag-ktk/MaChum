package com.ninetag.machum.external

import kotlinx.serialization.json.Json

/** A missing key is null; unsupported YAML is preserved and never silently converted. */
data class GeneralSourceValue(val value: String?, val error: String? = null)

/** Replaces only the source line, preserving all other Markdown bytes and line endings. */
object GeneralSourceProperty {
    private val sourceKey = Regex("^(?:source|\"source\"|'source')\\s*:")
    private val nonText = Regex("(?i)(?:null|~|true|false|yes|no|on|off|[-+]?(?:[0-9][0-9_]*(?:\\.[0-9_]*)?(?:[eE][-+]?[0-9]+)?|\\.[0-9]+)|[0-9]{4}-[0-9]{2}-[0-9]{2}.*|[-+]?\\.(?:inf|nan))")
    private val nonDecimalNumber = Regex("(?i)[-+]?(?:0[xob][0-9a-f_]+|[0-9][0-9_]*(?::[0-9_]+)+(?:\\.[0-9_]*)?)")
    private data class Located(val start: Int, val end: Int, val text: String, val newline: String, val insertAt: Int)

    fun read(raw: String): GeneralSourceValue = runCatching {
        val source = locate(raw) ?: return@runCatching GeneralSourceValue(null)
        if (source.start < 0) return@runCatching GeneralSourceValue(null)
        val value = source.text.substringAfter(':').trim()
        if (value.startsWith('"')) {
            // JSON quoting is a YAML subset. Unknown YAML escapes stay untouched.
            val match = Regex("^\"(?:[^\"\\\\]|\\\\.)*\"").find(value)
                ?: error("source 문자열의 따옴표를 확인해 주세요.")
            val trailing = value.substring(match.value.length).trim()
            check(trailing.isEmpty() || trailing.startsWith('#')) { "source는 텍스트 값 하나여야 합니다." }
            GeneralSourceValue(Json.decodeFromString<String>(match.value))
        } else if (value.startsWith('\'')) {
            val match = Regex("^'(?:[^']|'')*'").find(value) ?: error("source 문자열의 따옴표를 확인해 주세요.")
            val trailing = value.substring(match.value.length).trim()
            check(trailing.isEmpty() || trailing.startsWith('#')) { "source는 텍스트 값 하나여야 합니다." }
            GeneralSourceValue(match.value.drop(1).dropLast(1).replace("''", "'"))
        } else {
            val plain = value.substringBefore(" #").trim()
            check(plain.isNotEmpty() && plain.first() !in "[{|>!&*#@`" &&
                !nonText.matches(plain) && !nonDecimalNumber.matches(plain) &&
                ": " !in plain && !plain.startsWith("- ") && !plain.startsWith("? ")) {
                "source의 기존 비텍스트 값을 보존했습니다. 텍스트 값 하나로 직접 정리해 주세요."
            }
            GeneralSourceValue(plain)
        }
    }.getOrElse { GeneralSourceValue(null, it.message ?: "source를 읽을 수 없습니다.") }

    fun write(raw: String, value: String): String {
        val previous = read(raw)
        check(previous.error == null) { previous.error.orEmpty() }
        if (previous.value == value) return raw
        val source = locate(raw)
        val line = "source: " + Json.encodeToString(value)
        if (source == null) {
            val bom = if (raw.startsWith('\uFEFF')) "\uFEFF" else ""
            val body = raw.removePrefix(bom)
            val newline = if ("\r\n" in body) "\r\n" else "\n"
            return "$bom---$newline$line$newline---$newline$body"
        }
        if (source.start < 0) return raw.substring(0, source.insertAt) + line + source.newline + raw.substring(source.insertAt)
        // Source comments are part of the edited field; other lines remain byte-for-byte intact.
        return raw.substring(0, source.start) + line + raw.substring(source.end)
    }

    private fun locate(raw: String): Located? {
        val offset = if (raw.startsWith('\uFEFF')) 1 else 0
        val newline = when {
            raw.startsWith("---\r\n", offset) -> "\r\n"
            raw.startsWith("---\n", offset) -> "\n"
            else -> return null
        }
        var position = offset + 3 + newline.length
        var found: Located? = null
        var sourceSeen = false
        while (position <= raw.length) {
            val end = raw.indexOf(newline, position).takeIf { it >= 0 } ?: raw.length
            val line = raw.substring(position, end)
            if (line == "---" || line == "...") return found ?: Located(-1, -1, "", newline, position)
            if (sourceKey.containsMatchIn(line)) {
                check(found == null) { "중복 source key를 먼저 정리해 주세요." }
                found = Located(position, end, line, newline, position)
                sourceSeen = true
            } else if (line.isNotBlank() && !line.trimStart().startsWith('#')) {
                check(!sourceSeen || (!line.first().isWhitespace() && !line.startsWith('-'))) {
                    "여러 줄 또는 목록 source는 자동 변경하지 않습니다."
                }
                sourceSeen = false
            }
            if (end == raw.length) break
            position = end + newline.length
        }
        error("닫히지 않은 frontmatter는 변경하지 않습니다.")
    }
}
