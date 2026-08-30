package com.ninetag.machum.screen.mainComposition

import com.ninetag.machum.external.FileKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedSaveCoordinatorTest {

    @Test
    fun sameKey_onlyLatestValueIsSaved() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(saved)

        coordinator.schedule("a.md", "old")
        advanceTimeBy(300.milliseconds)
        coordinator.schedule("a.md", "new")
        advanceTimeBy(499.milliseconds)
        runCurrent()
        assertTrue(saved.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf("a.md" to "new"), saved)
    }

    @Test
    fun differentKeys_areDebouncedIndependently() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(saved)

        coordinator.schedule("a.md", "A")
        advanceTimeBy(250.milliseconds)
        coordinator.schedule("b.md", "B")
        advanceTimeBy(250.milliseconds)
        runCurrent()
        assertEquals(listOf("a.md" to "A"), saved)

        advanceTimeBy(250.milliseconds)
        runCurrent()
        assertEquals(listOf("a.md" to "A", "b.md" to "B"), saved)
    }

    @Test
    fun sameFileNameInDifferentFolders_isDebouncedIndependently() = runTest {
        val saved = mutableListOf<Pair<FileKey, String>>()
        val coordinator = DebouncedSaveCoordinator<FileKey, String>(
            scope = this,
            debounceMillis = 500,
        ) { key, value -> saved += key to value }
        val scene = FileKey.of("Scene/same.md")
        val character = FileKey.of("Character/same.md")

        coordinator.schedule(scene, "scene")
        coordinator.schedule(character, "character")
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertEquals(listOf(scene to "scene", character to "character"), saved)
    }

    @Test
    fun cancel_preventsStaleSaveAfterExternalChange() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(saved)

        coordinator.schedule("a.md", "local")
        advanceTimeBy(300.milliseconds)
        coordinator.cancel("a.md")
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertTrue(saved.isEmpty())
    }

    @Test
    fun cancelMissing_cancelsDeletedFileOnly() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(saved)

        coordinator.schedule("deleted.md", "deleted")
        coordinator.schedule("kept.md", "kept")
        coordinator.cancelMissing(setOf("kept.md"))
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertEquals(listOf("kept.md" to "kept"), saved)
    }

    @Test
    fun cancelAll_preventsEveryPendingSave() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(saved)

        coordinator.schedule("a.md", "A")
        coordinator.schedule("b.md", "B")
        coordinator.cancelAll()
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertTrue(saved.isEmpty())
    }

    @Test
    fun flush_savesRequestedPendingValueImmediately() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(saved)

        coordinator.schedule("plot.md", "edited")
        coordinator.schedule("other.md", "later")
        coordinator.flush(setOf("plot.md"))

        assertEquals(listOf("plot.md" to "edited"), saved)
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(listOf("plot.md" to "edited", "other.md" to "later"), saved)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        saved: MutableList<Pair<String, String>>,
    ): DebouncedSaveCoordinator<String, String> = DebouncedSaveCoordinator(
        scope = this,
        debounceMillis = 500,
    ) { key, value -> saved += key to value }
}
