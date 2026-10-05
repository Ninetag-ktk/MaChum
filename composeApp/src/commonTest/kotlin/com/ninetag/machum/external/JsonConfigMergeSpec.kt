package com.ninetag.machum.external

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonConfigMergeSpec {

    private val json = Json { encodeDefaults = true }

    @Test
    fun topLevelUnknownFieldsArePreserved() {
        val raw = """{"version":1,"config":{"type":"default"},"legacy":{"id":"A"}}"""
        val previous = """{"config":{"type":"default"}}"""
        val next = """{"config":{"type":"general"}}"""

        val merged = mergeKnownConfig(raw, previous, next)
        val parsed = json.parseToJsonElement(merged).jsonObject
        val config = parsed["config"]!!.jsonObject

        assertEquals("general", config["type"]!!.jsonPrimitive.content)
        assertEquals("A", parsed["legacy"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(3, parsed.size)
    }

    @Test
    fun nestedUnknownFieldsArePreservedInsideKnownObjects() {
        val raw = """{"scope":{"a":1,"b":{"nested":"keep","x":2},"legacyNote":"v1"},"other":5}"""
        val previous = """{"scope":{"a":1,"b":{"x":2}}}"""
        val next = """{"scope":{"a":1,"b":{"x":3}}}"""

        val merged = mergeKnownConfig(raw, previous, next)
        val parsed = json.parseToJsonElement(merged).jsonObject
        val scope = parsed["scope"]!!.jsonObject

        assertEquals(3, scope.size)
        assertEquals(2, scope["b"]!!.jsonObject.size)
        assertEquals("keep", scope["b"]!!.jsonObject["nested"]!!.jsonPrimitive.content)
        assertEquals(5, parsed["other"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun knownChangesAreAppliedAndReflected() {
        val raw = """{"config":{"x":1,"y":2},"other":"ok"}"""
        val previous = """{"config":{"x":1,"y":2}}"""
        val next = """{"config":{"x":2,"y":2}}"""

        val merged = mergeKnownConfig(raw, previous, next)
        val parsed = json.parseToJsonElement(merged).jsonObject

        assertEquals(2, parsed["config"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, parsed["config"]!!.jsonObject["y"]!!.jsonPrimitive.content.toInt())
        assertEquals("ok", parsed["other"]!!.jsonPrimitive.content)
    }

    @Test
    fun nestedMapKeyDeletionAndAdditionAreApplied() {
        val raw = """{"propertyTypes":{"title":"text","tags":"list","legacy":true,"legacyType":{"enabled":true}},"meta":"keep"}"""
        val previous = """{"propertyTypes":{"title":"text","tags":"list"}}"""
        val next = """{"propertyTypes":{"title":"text","source":"text"}}"""

        val merged = mergeKnownConfig(raw, previous, next)
        val parsed = json.parseToJsonElement(merged).jsonObject
        val propertyTypes = parsed["propertyTypes"]!!.jsonObject

        assertEquals(4, propertyTypes.size)
        assertEquals("text", propertyTypes["title"]!!.jsonPrimitive.content)
        assertEquals("text", propertyTypes["source"]!!.jsonPrimitive.content)
        assertEquals(false, propertyTypes.containsKey("tags"))
        assertEquals(true, propertyTypes.containsKey("legacy"))
        assertEquals(true, propertyTypes["legacy"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, propertyTypes["legacyType"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("keep", parsed["meta"]!!.jsonPrimitive.content)
    }

    @Test
    fun unchangedKnownSubtreePreservesLegacyRawExpression() {
        val raw = """
            {
              "scope":{"a":1,"b":{"x":1,"legacyTag":"keep"},"c":3},
              "settings":{"v":1},
              "version":2
            }
        """.trimIndent()
        val previous = """{"scope":{"a":1,"b":{"x":1},"c":3},"settings":{"v":1}}"""
        val next = """{"scope":{"a":1,"b":{"x":1},"c":3},"settings":{"v":2}}"""

        val merged = mergeKnownConfig(raw, previous, next)
        val parsed = json.parseToJsonElement(merged).jsonObject
        val expectedScope = json.parseToJsonElement("""{"a":1,"b":{"x":1,"legacyTag":"keep"},"c":3}""")
        val mergedScope: JsonElement = parsed["scope"]!!

        assertEquals(expectedScope, mergedScope)
        assertEquals(2, parsed["settings"]!!.jsonObject["v"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun movedObjectEntryKeepsUnknownNestedFields() {
        val raw = """{"folders":{"Old":{"type":"general","future":{"kept":true}},"Other":{}}}"""

        val moved = json.parseToJsonElement(moveJsonObjectEntry(raw, "folders", "Old", "New")).jsonObject
        val folders = moved.getValue("folders").jsonObject

        assertEquals(false, "Old" in folders)
        assertEquals(true, folders.getValue("New").jsonObject.getValue("future").jsonObject.getValue("kept").jsonPrimitive.content.toBoolean())
    }
}
