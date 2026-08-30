package com.ninetag.machum.screen.mainComposition

import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderConfigEditorStateTest {
    @Test
    fun `tags are normalized and deduplicated`() {
        assertEquals(
            listOf("캐릭터_설정", "설정"),
            parseFolderAutoTags(" #캐릭터 설정, 설정, 캐릭터_설정, "),
        )
    }

    @Test
    fun `selecting general disables plot`() {
        val state = FolderConfigEditorState(
            FolderConfig(type = FolderType.DEFAULT, plotEnabled = true),
        )

        state.selectType(FolderType.GENERAL)

        assertEquals(FolderType.GENERAL, state.type)
        assertFalse(state.plotEnabled)
        assertFalse(state.config.plotEnabled)
    }

    @Test
    fun `plot can only be enabled for default`() {
        val state = FolderConfigEditorState(FolderConfig(type = FolderType.GENERAL))

        state.updatePlotEnabled(true)
        assertFalse(state.plotEnabled)

        state.selectType(FolderType.DEFAULT)
        state.updatePlotEnabled(true)
        assertTrue(state.plotEnabled)
    }
}
