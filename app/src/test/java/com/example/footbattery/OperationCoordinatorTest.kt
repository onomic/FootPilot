package com.example.footbattery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OperationCoordinatorTest {
    @Test fun duplicateStandbyRequestIsRejected() = runBlocking {
        val coordinator = OperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            coordinator.runStandby(BleOperationKind.STANDBY_ON) {
                entered.complete(Unit)
                release.await()
                "done"
            }
        }

        entered.await()
        val duplicate = coordinator.runStandby(BleOperationKind.STANDBY_OFF) { "unexpected" }
        assertSame(CoordinatedResult.Busy, duplicate)

        release.complete(Unit)
        val completed = first.await() as CoordinatedResult.Completed
        assertEquals("done", completed.value)
    }

    @Test fun standbyWaitsForAnExistingCheckAndBlocksNewChecks() = runBlocking {
        val coordinator = OperationCoordinator()
        val checkEntered = CompletableDeferred<Unit>()
        val finishCheck = CompletableDeferred<Unit>()
        val check = async {
            coordinator.tryRun(BleOperationKind.MANUAL_CHECK) {
                checkEntered.complete(Unit)
                finishCheck.await()
            }
        }
        checkEntered.await()

        val standby = async {
            coordinator.runStandby(BleOperationKind.STANDBY_ON) { "standby" }
        }
        while (coordinator.state.value.standbyPending == null) yield()

        assertSame(
            CoordinatedResult.Busy,
            coordinator.tryRun(BleOperationKind.SCHEDULED_CHECK) { "unexpected" }
        )
        finishCheck.complete(Unit)
        check.await()

        val completed = standby.await() as CoordinatedResult.Completed
        assertEquals("standby", completed.value)
    }
}
