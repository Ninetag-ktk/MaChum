package com.ninetag.machum.screen.mainComposition

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSaveCoordinatorTest {

    @Test
    fun runAfterFlush_savesDebouncedValueBeforeTransition() = runTest {
        val events = mutableListOf<String>()
        val pending = DebouncedSaveCoordinator<String, String>(
            scope = this,
            debounceMillis = 500,
        ) { key, value -> events += "save:$key=$value" }
        val workspace = WorkspaceSaveCoordinator().apply {
            register(pending::flushAll)
        }
        pending.schedule("draft.md", "latest")

        val result = workspace.runAfterFlush {
            events += "transition"
        }

        assertTrue(result.isSuccess)
        assertEquals(listOf("save:draft.md=latest", "transition"), events)
    }

    @Test
    fun runAfterFlush_doesNotTransitionWhenSaveFails() = runTest {
        var transitioned = false
        val workspace = WorkspaceSaveCoordinator().apply {
            register { error("disk unavailable") }
        }

        val result = workspace.runAfterFlush {
            transitioned = true
        }

        assertTrue(result.isFailure)
        assertFalse(transitioned)
        assertNotNull(workspace.lastErrorMessage.value)
    }
}
