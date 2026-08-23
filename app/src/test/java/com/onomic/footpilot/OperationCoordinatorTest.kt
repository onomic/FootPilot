package com.onomic.footpilot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun everyDeviceControlKindUsesTheSamePriorityReservation() = runBlocking {
        val coordinator = OperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auto = async {
            coordinator.runDeviceControl(BleOperationKind.AUTO_ALIGN) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        assertSame(
            CoordinatedResult.Busy,
            coordinator.runDeviceControl(BleOperationKind.PRESET_APPLY) { Unit }
        )
        assertSame(
            CoordinatedResult.Busy,
            coordinator.tryRun(BleOperationKind.SCHEDULED_CHECK) { Unit }
        )
        assertSame(
            CoordinatedResult.Busy,
            coordinator.tryRun(BleOperationKind.MANUAL_CHECK) { Unit }
        )

        release.complete(Unit)
        auto.await()
        Unit
    }

    @Test fun allChairAndRelaxMutationsUseDeviceControlReservation() = runBlocking {
        val kinds = listOf(
            BleOperationKind.CHAIR_EXIT_ON,
            BleOperationKind.CHAIR_EXIT_OFF,
            BleOperationKind.RELAX_ON,
            BleOperationKind.RELAX_OFF
        )
        kinds.forEach { kind ->
            val coordinator = OperationCoordinator()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mode = async {
                coordinator.runDeviceControl(kind) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            assertSame(
                CoordinatedResult.Busy,
                coordinator.tryRun(BleOperationKind.FOOT_MODES_REFRESH) { Unit }
            )
            release.complete(Unit)
            mode.await()
        }
    }

    @Test fun fineAdjustmentBlocksCheckWhileDisconnectWaitsForSafeRelease() = runBlocking {
        val coordinator = OperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fine = async {
            coordinator.runDeviceControl(BleOperationKind.ANKLE_ADJUST) {
                entered.complete(Unit)
                release.await()
                "fine"
            }
        }
        entered.await()

        assertSame(
            CoordinatedResult.Busy,
            coordinator.tryRun(BleOperationKind.NOTIFICATION_CHECK) { "unexpected" }
        )
        val disconnect = async {
            coordinator.runQueued(BleOperationKind.DISCONNECT) { "disconnect" }
        }
        yield()
        assertFalse(disconnect.isCompleted)

        release.complete(Unit)
        assertEquals("fine", (fine.await() as CoordinatedResult.Completed).value)
        assertEquals("disconnect", (disconnect.await() as CoordinatedResult.Completed).value)
    }

    @Test fun temporaryOperationRemainsBusyWhileItsReleaseBarrierIsPending() = runBlocking {
        val coordinator = OperationCoordinator()
        val protocolComplete = CompletableDeferred<Unit>()
        val releaseComplete = CompletableDeferred<Unit>()
        val temporary = async {
            coordinator.tryRun(BleOperationKind.MANUAL_CHECK) {
                protocolComplete.complete(Unit)
                releaseComplete.await()
            }
        }

        protocolComplete.await()
        assertTrue(coordinator.isBusy())
        assertSame(
            CoordinatedResult.Busy,
            coordinator.tryRun(BleOperationKind.LIVE_CONNECT) { Unit }
        )

        releaseComplete.complete(Unit)
        temporary.await()
        assertFalse(coordinator.isBusy())
    }
}
