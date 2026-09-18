package com.ninetag.machum.external

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val mergeJson = Json {
    encodeDefaults = true
}

/**
 * Preserves unknown JSON fields from a previous raw payload while applying known field updates.
 *
 * `rawJson` is the original stored JSON, `previousKnownJson` is the previous
 * deserialized representation of known fields, and `nextKnownJson` is the
 * updated known representation.
 */
fun mergeKnownConfig(rawJson: String, previousKnownJson: String, nextKnownJson: String): String {
    val raw = mergeJson.parseToJsonElement(rawJson)
    val previous = mergeJson.parseToJsonElement(previousKnownJson)
    val next = mergeJson.parseToJsonElement(nextKnownJson)

    val merged = mergeValues(raw, previous, next)
    return mergeJson.encodeToString(JsonElement.serializer(), merged)
}

/** Moves one entry inside a JSON object while preserving the entry's complete raw JSON value. */
fun moveJsonObjectEntry(rawJson: String, objectKey: String, previousKey: String, nextKey: String): String {
    if (previousKey == nextKey) return rawJson
    val root = mergeJson.parseToJsonElement(rawJson) as? JsonObject ?: return rawJson
    val container = root[objectKey] as? JsonObject ?: return rawJson
    val value = container[previousKey] ?: return rawJson
    require(nextKey !in container) { "target JSON entry already exists: $nextKey" }
    val moved = LinkedHashMap<String, JsonElement>()
    container.forEach { (key, item) -> moved[if (key == previousKey) nextKey else key] = if (key == previousKey) value else item }
    return mergeJson.encodeToString(
        JsonElement.serializer(),
        JsonObject(LinkedHashMap(root).apply { put(objectKey, JsonObject(moved)) }),
    )
}

private fun mergeValues(raw: JsonElement, previousKnown: JsonElement, nextKnown: JsonElement): JsonElement {
    if (previousKnown == nextKnown) return raw
    if (raw is JsonObject && previousKnown is JsonObject && nextKnown is JsonObject) {
        return mergeObjects(raw, previousKnown, nextKnown)
    }
    return nextKnown
}

private fun mergeObjects(rawObject: JsonObject, previousKnown: JsonObject, nextKnown: JsonObject): JsonObject {
    val merged = LinkedHashMap<String, JsonElement>()

    for ((key, rawValue) in rawObject) {
        val wasKnownBefore = previousKnown.containsKey(key)
        val isKnownNow = nextKnown.containsKey(key)

        when {
            wasKnownBefore && isKnownNow -> {
                val previousChild = checkNotNull(previousKnown[key])
                val nextChild = checkNotNull(nextKnown[key])
                merged[key] = mergeValues(rawValue, previousChild, nextChild)
            }

            // Preserve top-level + nested unknown fields.
            !wasKnownBefore && !isKnownNow -> {
                merged[key] = rawValue
            }

            // Field exists only in previous config -> removed from known config.
            wasKnownBefore && !isKnownNow -> {
                continue
            }

            // Field promoted/introduced: new known field takes precedence.
            !wasKnownBefore && isKnownNow -> {
                merged[key] = checkNotNull(nextKnown[key])
            }
        }
    }

    for ((key, nextChild) in nextKnown) {
        if (!merged.containsKey(key)) {
            merged[key] = nextChild
        }
    }

    return JsonObject(merged)
}
