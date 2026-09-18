package com.ninetag.machum.external

import com.ninetag.machum.entity.DocumentPropertyType
import com.ninetag.machum.entity.documentPropertyKeyError

sealed interface DocumentPropertyValue {
    data class Text(val value: String) : DocumentPropertyValue
    data class ListValue(val items: List<DocumentPropertyListItem>) : DocumentPropertyValue
    data class NumberValue(val value: String) : DocumentPropertyValue
    data class BooleanValue(val value: Boolean) : DocumentPropertyValue
    data class DateValue(val value: String) : DocumentPropertyValue
    data class DateTimeValue(val value: String) : DocumentPropertyValue
}

sealed interface DocumentPropertyListItem {
    data class Text(val value: String) : DocumentPropertyListItem
    data class NumberValue(val value: String) : DocumentPropertyListItem
}

data class DocumentProperty(
    val key: String,
    val type: DocumentPropertyType,
    val value: DocumentPropertyValue?,
    val readOnly: Boolean = false,
    val error: String? = null,
    /** Type represented by the YAML token itself. Null means an intentionally empty `key:` value. */
    val sourceType: DocumentPropertyType? = type,
    /** True when [type] came from the workspace registry instead of semantic/YAML fallback. */
    val typeFromSettings: Boolean = false,
) {
    val hasTypeMismatch: Boolean
        get() = sourceType != null && sourceType != type && sourceType != DocumentPropertyType.UNSUPPORTED
}

data class DocumentProperties(
    val properties: List<DocumentProperty>,
    val hasFrontMatter: Boolean,
    val error: String? = null,
)

sealed interface DocumentPropertyResult {
    data class Success(val raw: String) : DocumentPropertyResult
    data class Failure(val message: String) : DocumentPropertyResult
}

fun parseDocumentProperties(
    raw: String,
    typeHints: Map<String, DocumentPropertyType> = emptyMap(),
): DocumentProperties = parseDocumentPropertiesInternal(raw, typeHints).publicResult

/** Returns the exact source block for one unambiguous top-level property. */
fun documentPropertySource(raw: String, key: String): String? {
    val entries = parseDocumentPropertiesInternal(raw).entries.filter { it.key == key }
    if (entries.isEmpty()) return null
    return entries.joinToString(separator = "") { raw.substring(it.start, it.end) }
}

fun setDocumentProperty(
    raw: String,
    key: String,
    value: DocumentPropertyValue?,
): DocumentPropertyResult {
    val keyError = documentPropertyKeyError(key)
    if (keyError != null) return DocumentPropertyResult.Failure(keyError)
    val rendered = renderPropertyValue(value)
    if (rendered is RenderedValue.Failure) return DocumentPropertyResult.Failure(rendered.message)

    val parsed = parseDocumentPropertiesInternal(raw)
    parsed.publicResult.error?.let { return DocumentPropertyResult.Failure(it) }
    val matches = parsed.entries.filter { it.key == key }
    if (matches.size > 1) return DocumentPropertyResult.Failure("Duplicate property key: $key")
    if (matches.singleOrNull()?.property?.readOnly == true) {
        return DocumentPropertyResult.Failure(matches.single().property.error ?: "Property is read-only: $key")
    }

    val lineEnding = parsed.lineEnding
    val newEntry = renderNewEntry(key, rendered, lineEnding)
    val result = if (!parsed.hasFrontMatter) {
        val bom = if (raw.startsWith(BOM)) BOM else ""
        val content = raw.removePrefix(BOM)
        "$bom---$lineEnding$newEntry---$lineEnding$content"
    } else if (matches.isEmpty()) {
        raw.substring(0, parsed.closingStart) + newEntry + raw.substring(parsed.closingStart)
    } else {
        replaceEntryValue(raw, matches.single(), value, rendered, lineEnding)
    }
    return DocumentPropertyResult.Success(result)
}

fun deleteDocumentProperty(raw: String, key: String): DocumentPropertyResult {
    val parsed = parseDocumentPropertiesInternal(raw)
    parsed.publicResult.error?.let { return DocumentPropertyResult.Failure(it) }
    val matches = parsed.entries.filter { it.key == key }
    if (matches.size > 1) return DocumentPropertyResult.Failure("Duplicate property key: $key")
    val entry = matches.singleOrNull() ?: return DocumentPropertyResult.Failure("Property not found: $key")
    if (entry.property.readOnly) {
        return DocumentPropertyResult.Failure(entry.property.error ?: "Property is read-only: $key")
    }
    return DocumentPropertyResult.Success(raw.removeRange(entry.start, entry.end))
}

fun renameDocumentProperty(raw: String, oldKey: String, newKey: String): DocumentPropertyResult {
    val keyError = documentPropertyKeyError(newKey)
    if (keyError != null) return DocumentPropertyResult.Failure(keyError)
    val parsed = parseDocumentPropertiesInternal(raw)
    parsed.publicResult.error?.let { return DocumentPropertyResult.Failure(it) }
    val oldMatches = parsed.entries.filter { it.key == oldKey }
    if (oldMatches.size > 1) return DocumentPropertyResult.Failure("Duplicate property key: $oldKey")
    val entry = oldMatches.singleOrNull() ?: return DocumentPropertyResult.Failure("Property not found: $oldKey")
    if (entry.property.readOnly) {
        return DocumentPropertyResult.Failure(entry.property.error ?: "Property is read-only: $oldKey")
    }
    if (oldKey != newKey && parsed.entries.any { it.key == newKey }) {
        return DocumentPropertyResult.Failure("Duplicate property key: $newKey")
    }
    return DocumentPropertyResult.Success(raw.replaceRange(entry.keyStart, entry.keyEnd, newKey))
}

private const val BOM = "\uFEFF"
private val NUMBER = Regex("[-+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val DATE_TIME = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt ][0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?(?:[Zz]|[-+][0-9]{2}:[0-9]{2})?")

private data class SourceLine(val start: Int, val contentEnd: Int, val end: Int, val content: String)
private data class ParsedEntry(
    val key: String,
    val property: DocumentProperty,
    val start: Int,
    val end: Int,
    val keyStart: Int,
    val keyEnd: Int,
    val colon: Int,
)
private data class InternalParse(
    val publicResult: DocumentProperties,
    val entries: List<ParsedEntry>,
    val hasFrontMatter: Boolean,
    val closingStart: Int,
    val lineEnding: String,
)

private fun parseDocumentPropertiesInternal(
    raw: String,
    typeHints: Map<String, DocumentPropertyType> = emptyMap(),
): InternalParse {
    val lines = splitLines(raw)
    val first = lines.firstOrNull()
    val marker = first?.content?.removePrefix(BOM)
    val lineEnding = if (raw.contains("\r\n")) "\r\n" else "\n"
    if (marker != "---") {
        return InternalParse(DocumentProperties(emptyList(), false), emptyList(), false, 0, lineEnding)
    }
    val closingIndex = (1 until lines.size).firstOrNull { lines[it].content == "---" || lines[it].content == "..." }
        ?: return InternalParse(
            DocumentProperties(emptyList(), true, "Frontmatter closing delimiter is missing"),
            emptyList(), true, raw.length, lineEnding,
        )

    val entries = mutableListOf<ParsedEntry>()
    val errors = mutableListOf<String>()
    var index = 1
    while (index < closingIndex) {
        val line = lines[index]
        if (line.content.isBlank() || line.content.trimStart().startsWith("#")) {
            index++
            continue
        }
        if (line.content.firstOrNull()?.isWhitespace() == true) {
            errors += "Unexpected indented frontmatter content at line ${index + 1}"
            index++
            continue
        }
        val colon = findKeyColon(line.content)
        if (colon <= 0) {
            errors += "Unsupported frontmatter content at line ${index + 1}"
            index++
            continue
        }
        val rawKey = line.content.substring(0, colon)
        val keyToken = rawKey.trim()
        val decodedKey = parseQuoted(keyToken)
        val keyDecodeError = if (
            (keyToken.startsWith("\"") || keyToken.startsWith("'")) && decodedKey == null
        ) "Unsupported quoted property key" else null
        if (keyDecodeError != null) errors += keyDecodeError
        val key = decodedKey ?: keyToken
        var next = index + 1
        while (next < closingIndex && lines[next].content.firstOrNull()?.isWhitespace() == true) next++
        val valueText = line.content.substring(colon + 1)
        val continuation = lines.subList(index + 1, next).map { it.content }
        val parsedValue = parseValue(key, valueText, continuation)
        val fixedType = fixedPropertyType(key)
        val effectiveType = when {
            parsedValue.error != null -> DocumentPropertyType.UNSUPPORTED
            fixedType != null -> fixedType
            typeHints[key] != null && typeHints[key] != DocumentPropertyType.UNSUPPORTED -> typeHints.getValue(key)
            parsedValue.type != null -> parsedValue.type
            else -> DocumentPropertyType.TEXT
        }
        val property = DocumentProperty(
            key = key,
            type = if (keyDecodeError != null) DocumentPropertyType.UNSUPPORTED else effectiveType,
            value = if (keyDecodeError != null) null else parsedValue.value,
            readOnly = keyDecodeError != null || parsedValue.error != null,
            error = keyDecodeError ?: parsedValue.error,
            sourceType = if (keyDecodeError != null) DocumentPropertyType.UNSUPPORTED else parsedValue.type,
            typeFromSettings = fixedType == null && key in typeHints,
        )
        entries += ParsedEntry(
            key, property, line.start, if (continuation.isEmpty()) line.end else lines[next - 1].end,
            line.start + rawKey.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0),
            line.start + rawKey.indexOfLast { !it.isWhitespace() } + 1,
            line.start + colon,
        )
        index = next
    }
    val duplicateKeys = entries.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
    val protected = entries.map { entry ->
        if (entry.key in duplicateKeys) {
            val message = "Duplicate property key: ${entry.key}"
            entry.copy(property = entry.property.copy(readOnly = true, error = message))
        } else entry
    }
    return InternalParse(
        DocumentProperties(protected.map { it.property }, true, errors.firstOrNull()),
        protected,
        true,
        lines[closingIndex].start,
        lineEnding,
    )
}

private data class ParsedValue(
    val type: DocumentPropertyType?,
    val value: DocumentPropertyValue?,
    val error: String? = null,
)

private fun parseValue(key: String, afterColon: String, continuation: List<String>): ParsedValue {
    val (token, _) = splitYamlInlineComment(afterColon)
    val trimmed = token.trim()
    if (continuation.isNotEmpty()) {
        if (trimmed.isNotEmpty()) return unsupported("Nested or ambiguous YAML value")
        val meaningful = continuation.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val indentation = meaningful.firstOrNull()?.takeWhile { it == ' ' || it == '\t' }
        if (
            meaningful.size != continuation.size || indentation.isNullOrEmpty() ||
            meaningful.any { line ->
                line.takeWhile { it == ' ' || it == '\t' } != indentation ||
                    !line.removePrefix(indentation).startsWith("- ")
            }
        ) {
            return unsupported("Nested or ambiguous YAML value")
        }
        val items = meaningful.map { it.removePrefix(indentation).removePrefix("- ").trim() }
        if (items.any { splitYamlInlineComment(it).second.isNotEmpty() }) {
            return unsupported("Commented block lists are preserved as read-only")
        }
        val parsedItems = items.map { parseListItem(it) ?: return unsupported("Unsupported list item") }
        return listResult(key, parsedItems)
    }
    if (trimmed.isEmpty()) {
        val emptyList = if (fixedPropertyType(key) in setOf(DocumentPropertyType.LIST, DocumentPropertyType.TAGS)) {
            DocumentPropertyValue.ListValue(emptyList())
        } else {
            null
        }
        return ParsedValue(null, emptyList)
    }
    if (trimmed == "null" || trimmed == "~" || trimmed.startsWith("{") || trimmed.startsWith("&") || trimmed.startsWith("*")) {
        return unsupported("Nested or ambiguous YAML value")
    }
    if (trimmed.startsWith("[")) {
        if (!trimmed.endsWith("]")) return unsupported("Malformed inline list")
        val inside = trimmed.substring(1, trimmed.length - 1)
        if (inside.isBlank()) return listResult(key, emptyList())
        val parts = splitYamlInlineList(inside) ?: return unsupported("Unsupported inline list")
        val items = parts.map { parseListItem(it.trim()) ?: return unsupported("Unsupported list item") }
        return listResult(key, items)
    }
    val quoted = parseQuoted(trimmed)
    if (quoted != null) {
        return if (isSemanticListKey(key)) listResult(key, listOf(DocumentPropertyListItem.Text(quoted)))
        else ParsedValue(DocumentPropertyType.TEXT, DocumentPropertyValue.Text(quoted))
    }
    if (trimmed.startsWith("\"") || trimmed.startsWith("'")) return unsupported("Malformed quoted value")
    if (trimmed.equals("true", true) || trimmed.equals("false", true)) {
        return ParsedValue(DocumentPropertyType.BOOLEAN, DocumentPropertyValue.BooleanValue(trimmed.equals("true", true)))
    }
    if (trimmed.equals("null", true) || isYamlSpecialNumber(trimmed)) return unsupported("Unsupported YAML scalar")
    if (NUMBER.matches(trimmed)) return ParsedValue(DocumentPropertyType.NUMBER, DocumentPropertyValue.NumberValue(trimmed))
    if (DATE.matches(trimmed)) return if (isValidDate(trimmed)) {
        ParsedValue(DocumentPropertyType.DATE, DocumentPropertyValue.DateValue(trimmed))
    } else unsupported("Invalid calendar date")
    if (DATE_TIME.matches(trimmed)) return if (isValidDateTime(trimmed)) {
        ParsedValue(DocumentPropertyType.DATE_TIME, DocumentPropertyValue.DateTimeValue(trimmed))
    } else unsupported("Invalid date-time value")
    if (trimmed.startsWith("|") || trimmed.startsWith(">") || trimmed.contains("!!")) return unsupported("Unsupported YAML value")
    return if (isSemanticListKey(key)) listResult(key, listOf(DocumentPropertyListItem.Text(trimmed)))
    else ParsedValue(DocumentPropertyType.TEXT, DocumentPropertyValue.Text(trimmed))
}

private fun unsupported(message: String) = ParsedValue(DocumentPropertyType.UNSUPPORTED, null, message)

private fun listResult(key: String, items: List<DocumentPropertyListItem>) = ParsedValue(
    if (key == "tags") DocumentPropertyType.TAGS else DocumentPropertyType.LIST,
    DocumentPropertyValue.ListValue(items),
)

private fun fixedPropertyType(key: String): DocumentPropertyType? = when (key) {
    "tags" -> DocumentPropertyType.TAGS
    "aliases" -> DocumentPropertyType.LIST
    else -> null
}

private fun isSemanticListKey(key: String): Boolean = fixedPropertyType(key) in
    setOf(DocumentPropertyType.LIST, DocumentPropertyType.TAGS)

private fun parseListItem(raw: String): DocumentPropertyListItem? {
    val (token, _) = splitYamlInlineComment(raw)
    val value = token.trim()
    parseQuoted(value)?.let { return DocumentPropertyListItem.Text(it) }
    if (NUMBER.matches(value)) return DocumentPropertyListItem.NumberValue(value)
    if (
        value.isEmpty() || value.startsWith("[") || value.startsWith("{") ||
        value.startsWith("\"") || value.startsWith("'") ||
        value.startsWith("*") || value.startsWith("&") || value.startsWith("!") ||
        value.startsWith("|") || value.startsWith(">") ||
        value.equals("true", true) || value.equals("false", true) ||
        value.equals("null", true) || value == "~" || isYamlSpecialNumber(value) ||
        DATE.matches(value) || DATE_TIME.matches(value) ||
        value.contains(Regex("^.*:\\s"))
    ) return null
    return DocumentPropertyListItem.Text(value)
}

private sealed interface RenderedValue {
    data object Empty : RenderedValue
    data class Scalar(val raw: String) : RenderedValue
    data class ListItems(val items: List<DocumentPropertyListItem>) : RenderedValue
    data class Failure(val message: String) : RenderedValue
}

private fun renderPropertyValue(value: DocumentPropertyValue?): RenderedValue {
    return when (value) {
        null -> RenderedValue.Empty
        is DocumentPropertyValue.Text -> if (value.value.isEmpty()) {
            RenderedValue.Empty
        } else {
            RenderedValue.Scalar(encodeYamlText(value.value))
        }
        is DocumentPropertyValue.ListValue -> {
            val invalidNumber = value.items.filterIsInstance<DocumentPropertyListItem.NumberValue>()
                .firstOrNull { !NUMBER.matches(it.value) }
            if (invalidNumber != null) {
                RenderedValue.Failure("Invalid numeric list item")
            } else if (value.items.isEmpty()) {
                RenderedValue.Empty
            } else {
                RenderedValue.ListItems(value.items)
            }
        }
        is DocumentPropertyValue.NumberValue -> when {
            value.value.isEmpty() -> RenderedValue.Empty
            NUMBER.matches(value.value) -> RenderedValue.Scalar(value.value)
            else -> RenderedValue.Failure("Number value is invalid")
        }
        is DocumentPropertyValue.BooleanValue -> RenderedValue.Scalar(value.value.toString())
        is DocumentPropertyValue.DateValue -> when {
            value.value.isEmpty() -> RenderedValue.Empty
            isValidDate(value.value) -> RenderedValue.Scalar(value.value)
            else -> RenderedValue.Failure("Date value is invalid")
        }
        is DocumentPropertyValue.DateTimeValue -> when {
            value.value.isEmpty() -> RenderedValue.Empty
            isValidDateTime(value.value) -> RenderedValue.Scalar(value.value)
            else -> RenderedValue.Failure("Date-time value is invalid")
        }
    }
}

private fun renderNewEntry(key: String, rendered: RenderedValue, lineEnding: String): String = when (rendered) {
    RenderedValue.Empty -> "$key:$lineEnding"
    is RenderedValue.Scalar -> "$key: ${rendered.raw}$lineEnding"
    is RenderedValue.ListItems -> buildString {
        append(key).append(':').append(lineEnding)
        rendered.items.forEach { item ->
            append("  - ").append(renderListItem(item, flowCollection = false)).append(lineEnding)
        }
    }
    is RenderedValue.Failure -> error("A failed value cannot be rendered")
}

private fun replaceEntryValue(
    raw: String,
    entry: ParsedEntry,
    value: DocumentPropertyValue?,
    rendered: RenderedValue,
    lineEnding: String,
): String {
    val firstLineEnd = raw.indexOfAny(charArrayOf('\r', '\n'), entry.colon).let { if (it < 0) raw.length else it }
    val afterColon = raw.substring(entry.colon + 1, firstLineEnd)
    val (oldToken, comment) = splitYamlInlineComment(afterColon)
    val existingTerminator = when {
        raw.substring(entry.start, entry.end).endsWith("\r\n") -> "\r\n"
        raw.substring(entry.start, entry.end).endsWith("\n") -> "\n"
        raw.substring(entry.start, entry.end).endsWith("\r") -> "\r"
        else -> ""
    }
    val commentPrefix = if (comment.isEmpty()) "" else {
        afterColon.takeWhile { it == ' ' || it == '\t' }.ifEmpty { " " } + comment.trimStart()
    }
    val replacement = when (rendered) {
        RenderedValue.Empty -> commentPrefix + existingTerminator
        is RenderedValue.Scalar -> {
            val token = if (value is DocumentPropertyValue.Text) {
                renderTextUsingExistingStyle(value.value, oldToken.trim())
            } else {
                rendered.raw
            }
            val whitespace = afterColon.takeWhile { it == ' ' || it == '\t' }.ifEmpty { " " }
            whitespace + token + comment + existingTerminator
        }
        is RenderedValue.ListItems -> {
            if (oldToken.trim().startsWith("[")) {
                val items = rendered.items.joinToString(", ") { renderListItem(it, flowCollection = true) }
                val whitespace = afterColon.takeWhile { it == ' ' || it == '\t' }.ifEmpty { " " }
                whitespace + "[$items]" + comment + existingTerminator
            } else {
                val indentation = raw.substring(firstLineEnd, entry.end)
                    .split(lineEnding)
                    .firstOrNull { it.trimStart().startsWith("- ") }
                    ?.takeWhile { it == ' ' || it == '\t' }
                    .takeUnless { it.isNullOrEmpty() }
                    ?: "  "
                buildString {
                    append(commentPrefix).append(lineEnding)
                    rendered.items.forEachIndexed { index, item ->
                        append(indentation).append("- ").append(renderListItem(item, flowCollection = false))
                        if (index != rendered.items.lastIndex || existingTerminator.isNotEmpty()) append(lineEnding)
                    }
                }
            }
        }
        is RenderedValue.Failure -> error("A failed value cannot be rendered")
    }
    return raw.replaceRange(entry.colon + 1, entry.end, replacement)
}

private fun renderListItem(item: DocumentPropertyListItem, flowCollection: Boolean): String = when (item) {
    is DocumentPropertyListItem.Text -> encodeYamlText(item.value, flowCollection)
    is DocumentPropertyListItem.NumberValue -> item.value
}

private fun renderTextUsingExistingStyle(value: String, existingToken: String): String = when {
    existingToken.length >= 2 && existingToken.first() == '\'' && existingToken.last() == '\'' ->
        "'${value.replace("'", "''")}'"
    existingToken.length >= 2 && existingToken.first() == '"' && existingToken.last() == '"' ->
        encodeDoubleQuotedYamlText(value)
    else -> encodeYamlText(value)
}


private fun isYamlSpecialNumber(value: String): Boolean =
    value.equals(".nan", true) || value.equals(".inf", true) || value.equals("+.inf", true) || value.equals("-.inf", true)

private fun findKeyColon(line: String): Int {
    val first = line.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return -1
    val openingQuote = line[first].takeIf { it == '\'' || it == '"' }
        ?: return line.indexOf(':', startIndex = first)
    val quote = openingQuote
    var escaped = false
    for (index in first + 1 until line.length) {
        val char = line[index]
        if (escaped) escaped = false
        else if (char == '\\' && quote == '"') escaped = true
        else if (char == quote) return line.indexOf(':', startIndex = index + 1)
    }
    return -1
}

private fun isValidDate(value: String): Boolean {
    if (!DATE.matches(value)) return false
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    if (month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..days
}

private fun isValidDateTime(value: String): Boolean {
    if (!DATE_TIME.matches(value) || !isValidDate(value.substring(0, 10))) return false
    if (value.substring(11, 13).toInt() !in 0..23 || value.substring(14, 16).toInt() !in 0..59) return false
    if (value.length > 16 && value[16] == ':' && value.substring(17, 19).toInt() !in 0..59) return false
    val zone = value.indexOfAny(charArrayOf('+', '-'), startIndex = 16)
    return zone < 0 || (
        value.substring(zone + 1, zone + 3).toInt() in 0..23 &&
            value.substring(zone + 4, zone + 6).toInt() in 0..59
        )
}

private fun parseQuoted(raw: String): String? {
    if (raw.length < 2) return null
    if (raw.first() == '\'' && raw.last() == '\'') return raw.substring(1, raw.length - 1).replace("''", "'")
    if (raw.first() != '"' || raw.last() != '"') return null
    return decodeDoubleQuotedYamlTextOrNull(raw)
}

private fun splitLines(raw: String): List<SourceLine> {
    val result = mutableListOf<SourceLine>()
    var start = 0
    var index = 0
    while (index < raw.length) {
        if (raw[index] == '\r' || raw[index] == '\n') {
            val contentEnd = index
            if (raw[index] == '\r' && index + 1 < raw.length && raw[index + 1] == '\n') index++
            index++
            result += SourceLine(start, contentEnd, index, raw.substring(start, contentEnd))
            start = index
        } else index++
    }
    if (start <= raw.length) result += SourceLine(start, raw.length, raw.length, raw.substring(start))
    return result
}
