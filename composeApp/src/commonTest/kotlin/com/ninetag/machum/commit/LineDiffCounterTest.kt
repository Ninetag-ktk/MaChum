package com.ninetag.machum.commit

import kotlin.test.Test
import kotlin.test.assertEquals

class LineDiffCounterTest {
    @Test
    fun countsAddedDeletedAndReplacedLines() {
        assertEquals(LineDiffCounter.count(null, "one\ntwo"), LineChangeCount(2, 0))
        assertEquals(LineDiffCounter.count("one\ntwo", null), LineChangeCount(0, 2))
        assertEquals(
            LineDiffCounter.count(
                "same\nold\ntail",
                "same\nnew\nextra\ntail",
            ),
            LineChangeCount(2, 1),
        )
    }

    @Test
    fun unchangedContentHasNoLineChanges() {
        assertEquals(
            LineDiffCounter.count("one\ntwo", "one\ntwo"),
            LineChangeCount(0, 0),
        )
    }
}

