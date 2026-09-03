package com.ninetag.machum.markdown.ui.diagnostics

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorRecompositionDiagnosticsJvmTest {

    @Test
    fun trackerRecordsParentRecompositionWhenItsArgumentsStayStable() = runTest {
        val propertyName = "machum.editor.recomposition.metrics"
        val previousProperty = System.getProperty(propertyName)
        System.setProperty(propertyName, "true")
        EditorRecompositionDiagnostics.counter.reset()
        recompositionHostValue = 0

        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitApplier(), recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }

        try {
            val trigger = mutableIntStateOf(0)
            composition.setContent {
                RecompositionHost(trigger.intValue)
            }
            recomposer.awaitIdle()
            assertEquals(
                1,
                EditorRecompositionDiagnostics.counter.snapshot().single().count,
            )

            trigger.intValue += 1
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            if (frameClock.hasAwaiters) frameClock.sendFrame(1L)
            testScheduler.runCurrent()
            recomposer.awaitIdle()
            assertEquals(1, recompositionHostValue)
            assertEquals(
                2,
                EditorRecompositionDiagnostics.counter.snapshot().single().count,
            )
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
            EditorRecompositionDiagnostics.counter.reset()
            if (previousProperty == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previousProperty)
            }
        }
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}

private var recompositionHostValue = 0

@Composable
private fun RecompositionHost(value: Int) {
    SideEffect { recompositionHostValue = value }
    TrackEditorRecomposition(scope = "document", key = "stable")
}
