package com.ninetag.machum.markdown.ui.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorRecompositionCounterTest {

    @Test
    fun record_countsEachScopeAndKeyIndependently() {
        val counter = EditorRecompositionCounter()

        assertEquals(1, counter.record("document", "a").count)
        assertEquals(2, counter.record("document", "a").count)
        assertEquals(1, counter.record("block", "a").count)
        assertEquals(1, counter.record("document", "b").count)

        assertEquals(
            listOf(
                EditorRecompositionSample("block", "a", 1),
                EditorRecompositionSample("document", "a", 2),
                EditorRecompositionSample("document", "b", 1),
            ),
            counter.snapshot(),
        )
    }

    @Test
    fun reset_removesPreviousBaseline() {
        val counter = EditorRecompositionCounter()
        counter.record("block", "a")

        counter.reset()

        assertTrue(counter.snapshot().isEmpty())
        assertEquals(1, counter.record("block", "a").count)
    }
}
