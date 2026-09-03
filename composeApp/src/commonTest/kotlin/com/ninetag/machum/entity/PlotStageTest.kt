package com.ninetag.machum.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlotStageTest {

    @Test
    fun plotFileNamesStartAtOne() {
        assertEquals(1, PlotStage.FIRST_ORDER)
        assertEquals("3-1. 전환점", PlotStage.CRISIS.fileName(PlotStage.FIRST_ORDER, "전환점"))
        assertFailsWith<IllegalArgumentException> {
            PlotStage.CRISIS.fileName(0, "잘못된 전환점")
        }
    }
}
