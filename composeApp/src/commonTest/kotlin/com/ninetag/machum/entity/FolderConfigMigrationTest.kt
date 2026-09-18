package com.ninetag.machum.entity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderConfigMigrationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun legacyNumberedAndPlotTypesMigrateToDefaultOptions() {
        val legacy = """
            {
              "folders": {
                "": { "type": "numbered", "autoTags": ["원고"] },
                "Scene": { "type": "plot", "autoTags": ["장면"] }
              }
            }
        """.trimIndent()

        val config = json.decodeFromString(ProjectConfig.serializer(), legacy)

        assertEquals(
            FolderConfig(type = FolderType.DEFAULT, autoTags = listOf("원고")),
            config.folders[BASE_FOLDER_PATH],
        )
        assertEquals(
            FolderConfig(type = FolderType.DEFAULT, plotEnabled = true, autoTags = listOf("장면")),
            config.folders["Scene"],
        )

        val encoded = json.encodeToString(ProjectConfig.serializer(), config)
        assertFalse(encoded.contains("\"type\": \"numbered\""))
        assertFalse(encoded.contains("\"type\": \"plot\""))
        assertTrue(encoded.contains("\"type\": \"default\""))
    }

    @Test
    fun generalCannotRetainPlotOption() {
        val normalized = ProjectConfig(
            folders = mapOf(
                BASE_FOLDER_PATH to FolderConfig(type = FolderType.GENERAL, plotEnabled = true),
            ),
        ).withDefaultBaseFolder()

        assertEquals(FolderConfig(type = FolderType.GENERAL), normalized.folders[BASE_FOLDER_PATH])
    }

    @Test
    fun legacyFolderTypePreservesDefaultPropertyKeys() {
        val legacy = """
            { "folders": { "": { "type": "plot", "autoTags": ["원고"], "defaultPropertyKeys": [" title ", "", "title"] } } }
            """.trimIndent()

        val normalized = json.decodeFromString(ProjectConfig.serializer(), legacy).withDefaultBaseFolder()

        assertEquals(true, normalized.folders[BASE_FOLDER_PATH]?.isPlot)
        assertEquals(listOf("title"), normalized.folders[BASE_FOLDER_PATH]?.defaultPropertyKeys)
    }
}
