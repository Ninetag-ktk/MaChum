package com.ninetag.machum.markdown.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorDocumentValueCoordinatorTest {
    @Test
    fun initialSnapshotIsNotEmittedAsAnEdit() {
        val coordinator = EditorDocumentValueCoordinator("initial")

        assertFalse(
            coordinator.acceptInternal(
                value = "initial",
                expectedExternalValue = "initial",
                collectorRevision = coordinator.revision,
            )
        )
    }

    @Test
    fun parentEchoOfInternalEditDoesNotRecreateDocument() {
        val coordinator = EditorDocumentValueCoordinator("initial")
        val revision = coordinator.revision

        assertTrue(coordinator.acceptInternal("edited", "initial", revision))
        assertFalse(coordinator.acceptExternal("edited"))
        assertFalse(coordinator.acceptInternal("edited", "edited", revision))
    }

    @Test
    fun staleCollectorCannotOverwriteExternalReplacement() {
        val coordinator = EditorDocumentValueCoordinator("first")
        val staleRevision = coordinator.revision

        assertTrue(coordinator.acceptExternal("external"))
        assertFalse(
            coordinator.acceptInternal(
                value = "late first edit",
                expectedExternalValue = "external",
                collectorRevision = staleRevision,
            )
        )
        assertTrue(
            coordinator.acceptInternal(
                value = "external edit",
                expectedExternalValue = "external",
                collectorRevision = coordinator.revision,
            )
        )
    }

    @Test
    fun collectorWaitsUntilNewExternalValueIsAcknowledged() {
        val coordinator = EditorDocumentValueCoordinator("first")

        assertFalse(
            coordinator.acceptInternal(
                value = "late first edit",
                expectedExternalValue = "external",
                collectorRevision = coordinator.revision,
            )
        )
    }
}
