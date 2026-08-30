package com.ninetag.machum.external

import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.effectiveAutoTags
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagedTagMergeTest {

    @Test
    fun effectiveTagsCombineBaseAndCurrentFolderWithoutDuplicates() {
        val config = ProjectConfig(
            folders = mapOf(
                BASE_FOLDER_PATH to FolderConfig(autoTags = listOf("작품", "공통")),
                "Character" to FolderConfig(autoTags = listOf("캐릭터", "공통")),
            ),
        )

        assertEquals(listOf("작품", "공통"), config.effectiveAutoTags(BASE_FOLDER_PATH))
        assertEquals(
            listOf("작품", "공통", "캐릭터"),
            config.effectiveAutoTags("Character"),
        )
    }

    @Test
    fun mergeRemovesOldManagedTagsAndPreservesManualTags() {
        val merged = mergeManagedTags(
            existingTags = listOf("수동", "기존-base", "기존-folder"),
            previousManagedTags = listOf("기존-base", "기존-folder"),
            updatedManagedTags = listOf("신규-base", "신규-folder"),
        )

        assertEquals(listOf("수동", "신규-base", "신규-folder"), merged)
    }
}
