package com.ninetag.machum.screen.mainComposition

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LatestNavigationGateTest {

    @Test
    fun lateFirstRequestCannotApplyAfterSecondRequestArrives() = runTest {
        val gate = LatestNavigationGate()
        val first = gate.newRequest()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val applied = mutableListOf<String>()

        val firstJob = launch {
            gate.run(first) { isLatest ->
                firstStarted.complete(Unit)
                releaseFirst.await()
                if (isLatest()) applied += "A"
            }
        }
        firstStarted.await()

        val second = gate.newRequest()
        val secondJob = launch {
            gate.run(second) { isLatest ->
                if (isLatest()) applied += "B"
            }
        }

        releaseFirst.complete(Unit)
        firstJob.join()
        secondJob.join()

        assertEquals(listOf("B"), applied)
    }

    @Test
    fun requestThatIsAlreadyStaleDoesNotStart() = runTest {
        val gate = LatestNavigationGate()
        val stale = gate.newRequest()
        gate.newRequest()
        var started = false

        gate.run(stale) { started = true }

        assertEquals(false, started)
    }
}
