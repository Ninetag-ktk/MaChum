package com.ninetag.machum.entity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectConfigTest {

    // FileManager.configJson 과 동일 설정
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun roundTrip(config: ProjectConfig): ProjectConfig =
        json.decodeFromString(ProjectConfig.serializer(), json.encodeToString(ProjectConfig.serializer(), config))

    @Test
    fun roundTrip_preservesFoldersAndFileIds() {
        val config = ProjectConfig(
            folders = mapOf(
                "" to FolderConfig(FolderType.NUMBERED, listOf("당신을_구하던_삶")),
                "Character" to FolderConfig(FolderType.GENERAL, listOf("캐릭터")),
                "Scene" to FolderConfig(FolderType.PLOT, listOf("장면구상")),
            ),
            fileIds = mapOf("a1b2c3d4" to "0. 프롤로그"),
        )
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun folderType_serializesAsLowercase() {
        val out = json.encodeToString(
            ProjectConfig.serializer(),
            ProjectConfig(folders = mapOf("" to FolderConfig(FolderType.NUMBERED))),
        )
        assertTrue(out.contains("\"numbered\""), "type 는 소문자 numbered 로 직렬화되어야 함: $out")
        assertTrue(!out.contains("NUMBERED"), "enum 상수명이 그대로 새어나오면 안 됨: $out")
    }

    @Test
    fun decodes_legacyConfig_ignoringWorkflowFields() {
        // 구 스키마(.machum.json) 하위호환: workflow/workflowLastModified 는 무시하고 fileIds 는 보존
        val legacy = """
            { "workflow": "당신을_구하던_삶", "workflowLastModified": 1700000000000, "fileIds": { "id1": "0. 프롤로그" } }
        """.trimIndent()
        val config = json.decodeFromString(ProjectConfig.serializer(), legacy)
        assertTrue(config.folders.isEmpty())
        assertEquals(mapOf("id1" to "0. 프롤로그"), config.fileIds)
    }

    @Test
    fun decodes_emptyObject_toDefaults() {
        val config = json.decodeFromString(ProjectConfig.serializer(), "{}")
        assertTrue(config.folders.isEmpty())
        assertTrue(config.fileIds.isEmpty())
    }

    @Test
    fun folderConfig_defaults_whenPartiallySpecified() {
        val config = json.decodeFromString(
            ProjectConfig.serializer(),
            """{ "folders": { "Scene": {} } }""",
        )
        assertEquals(FolderConfig(FolderType.GENERAL, emptyList()), config.folders["Scene"])
    }
}
