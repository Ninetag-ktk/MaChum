package com.ninetag.machum.external

import kotlinx.serialization.json.Json

private val yamlBooleanOrNull = Regex("(?i)(?:null|~|true|false|yes|no|on|off)")
private val yamlDecimalNumber = Regex(
    "[-+]?(?:(?:0|[1-9][0-9_]*|0[0-9_]+)(?:\\.[0-9_]*)?|\\.[0-9_]+)(?:[eE][-+]?[0-9_]+)?",
)
private val yamlNonDecimalNumber = Regex(
    "(?i)[-+]?(?:0x[0-9a-f_]+|0o[0-7_]+|0b[01_]+|[0-9][0-9_]*(?::[0-9_]+)+(?:\\.[0-9_]*)?|\\.(?:inf|nan))",
)
private val yamlDateOrDateTime = Regex(
    "[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[Tt ](?:[0-9]{1,2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)(?:[ \\t]*(?:[Zz]|[-+][0-9]{1,2}(?::?[0-9]{2})?))?)?",
)
private val yamlLeadingIndicator = setOf(
    '-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`',
)

/**
 * Encodes a UI text value as a YAML scalar without adding quotes to ordinary prose.
 *
 * Ambiguous YAML primitives and syntax-sensitive text are quoted so the file keeps
 * the same string meaning when it is opened without MaChum's display-type settings.
 */
internal fun encodeYamlText(value: String, flowCollection: Boolean = false): String =
    if (isSafeYamlPlainText(value, flowCollection)) value else Json.encodeToString(value)

internal fun encodeDoubleQuotedYamlText(value: String): String = Json.encodeToString(value)

internal fun decodeYamlText(token: String): String {
    val trimmed = token.trim()
    return when {
        trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' ->
            decodeDoubleQuotedYamlTextOrNull(trimmed) ?: trimmed.substring(1, trimmed.lastIndex)
        trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'' ->
            trimmed.substring(1, trimmed.lastIndex).replace("''", "'")
        else -> trimmed
    }
}

internal fun decodeDoubleQuotedYamlTextOrNull(token: String): String? =
    runCatching { Json.decodeFromString<String>(token) }.getOrNull()

/** Splits a YAML plain/quoted scalar from a trailing inline comment without consuming `#` inside quotes. */
internal fun splitYamlInlineComment(raw: String): Pair<String, String> {
    var quote: Char? = null
    var escaped = false
    raw.forEachIndexed { index, char ->
        if (escaped) escaped = false
        else if (char == '\\' && quote == '"') escaped = true
        else if (quote != null && char == quote) quote = null
        else if (quote == null && (char == '\'' || char == '"')) quote = char
        else if (quote == null && char == '#' && (index == 0 || raw[index - 1].isWhitespace())) {
            var commentStart = index
            while (commentStart > 0 && raw[commentStart - 1].isWhitespace()) commentStart--
            return raw.substring(0, commentStart) to raw.substring(commentStart)
        }
    }
    return raw to ""
}

/** Splits the contents of a YAML flow list while retaining commas inside quoted text. */
internal fun splitYamlInlineList(raw: String): List<String>? {
    val result = mutableListOf<String>()
    var start = 0
    var quote: Char? = null
    var escaped = false
    raw.forEachIndexed { index, char ->
        if (escaped) escaped = false
        else if (char == '\\' && quote == '"') escaped = true
        else if (quote != null && char == quote) quote = null
        else if (quote == null && (char == '\'' || char == '"')) quote = char
        else if (quote == null && char == ',') {
            result += raw.substring(start, index)
            start = index + 1
        }
    }
    if (quote != null) return null
    result += raw.substring(start)
    return result
}

internal fun isSafeYamlPlainText(value: String, flowCollection: Boolean = false): Boolean {
    if (value.isEmpty() || value != value.trim()) return false
    if (value.any { it == '\n' || it == '\r' || it == '\t' || it.code < 0x20 }) return false
    if (value.first() in yamlLeadingIndicator) return false
    if (value == "---" || value == "...") return false
    if (value.endsWith(':') || Regex(":\\s").containsMatchIn(value)) return false
    if (Regex("(^|\\s)#").containsMatchIn(value)) return false
    if (value.any { it == '[' || it == ']' || it == '{' || it == '}' }) return false
    if (flowCollection && ',' in value) return false
    if (yamlBooleanOrNull.matches(value)) return false
    if (yamlDecimalNumber.matches(value) || yamlNonDecimalNumber.matches(value)) return false
    if (yamlDateOrDateTime.matches(value)) return false
    return true
}
