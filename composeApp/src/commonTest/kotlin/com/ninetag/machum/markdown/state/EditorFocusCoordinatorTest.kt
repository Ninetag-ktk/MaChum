package com.ninetag.machum.markdown.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorFocusCoordinatorTest {
    @Test
    fun latestRequestInvalidatesEarlierRequest() {
        val coordinator = EditorFocusCoordinator()
        val first = coordinator.request("first", CursorHint.End, preferBottomEntry = true)
        val second = coordinator.request("second", CursorHint.Start)

        assertFalse(coordinator.isCurrent(first))
        assertTrue(coordinator.isCurrent(second))
        assertEquals(second, coordinator.currentRequest())
        assertEquals(2L, coordinator.version)
    }

    @Test
    fun completingStaleRequestDoesNotClearLatestRequest() {
        val coordinator = EditorFocusCoordinator()
        val stale = coordinator.request("old")
        val latest = coordinator.request("new", CursorHint.AtOffset(3))

        assertFalse(coordinator.complete(stale))
        assertEquals(latest, coordinator.currentRequest())
        assertEquals(2L, coordinator.version)
    }

    @Test
    fun completingCurrentRequestClearsItAndAdvancesVersion() {
        val coordinator = EditorFocusCoordinator()
        val request = coordinator.request("target", CursorHint.AtX(12f, lastLine = true))

        assertTrue(coordinator.complete(request))
        assertNull(coordinator.currentRequest())
        assertEquals(2L, coordinator.version)
    }

    @Test
    fun cancelOnlyAffectsCurrentRequest() {
        val coordinator = EditorFocusCoordinator()
        val first = coordinator.request("first")
        val second = coordinator.request("second")

        assertFalse(coordinator.cancel(first))
        assertTrue(coordinator.cancel(second))
        assertNull(coordinator.currentRequest())
    }
}
