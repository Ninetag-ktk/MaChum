package com.ninetag.machum.screen.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SingleLineSubmitGateTest {
    @Test
    fun disabledSubmissionDoesNotConsumeGate() {
        var submissions = 0
        val gate = SingleLineSubmitGate()

        assertFalse(gate.submitIf(enabled = false) { submissions += 1 })
        assertTrue(gate.submitIf(enabled = true) { submissions += 1 })

        assertEquals(1, submissions)
    }

    @Test
    fun enabledSubmissionRunsOnlyOnceUntilReset() {
        var submissions = 0
        val gate = SingleLineSubmitGate()

        assertTrue(gate.submitIf(enabled = true) { submissions += 1 })
        assertFalse(gate.submitIf(enabled = true) { submissions += 1 })
        gate.reset()
        assertTrue(gate.submitIf(enabled = true) { submissions += 1 })

        assertEquals(2, submissions)
    }

    @Test
    fun synchronousFailureReopensGate() {
        val gate = SingleLineSubmitGate()

        assertFailsWith<IllegalStateException> {
            gate.submitIf(enabled = true) { error("failed") }
        }

        assertTrue(gate.submitIf(enabled = true) {})
    }
}
