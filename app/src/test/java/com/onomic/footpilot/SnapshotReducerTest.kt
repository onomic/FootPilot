package com.onomic.footpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotReducerTest {
    private val previous = SnapshotState(
        batteryLevel = 70,
        standby = StandbyState.OFF,
        lastChecked = 100L
    )

    @Test fun completeBatteryAndStandbyAdvancesLastChecked() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(85, StandbyState.ON, checkedAt = 200L)
        )

        assertEquals(SnapshotState(85, StandbyState.ON, 200L), result.snapshot)
        assertTrue(result.completeSnapshotSaved)
    }

    @Test fun batteryOnlyDoesNotAdvanceCompleteSnapshot() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(85, standby = null, checkedAt = 200L)
        )

        assertEquals(previous, result.snapshot)
        assertEquals(100L, result.snapshot.lastChecked)
        assertFalse(result.completeSnapshotSaved)
    }

    @Test fun batteryOnlyDoesNotReplacePersistedCompleteSnapshot() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(12, standby = null, checkedAt = 200L)
        )

        assertEquals(previous, result.snapshot)
        assertFalse(result.completeSnapshotSaved)
    }

    @Test fun batteryOnlyProducesFreshLiveValueAndRunsLowBatteryEvaluation() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(12, standby = null, checkedAt = 200L)
        )
        var liveLevel: Int? = null
        var evaluatedLevel: Int? = null

        val handled = FreshBatteryResultHandler.handle(
            result.freshBatteryLevel,
            updateLiveLevel = { liveLevel = it },
            evaluateLowBattery = { evaluatedLevel = it }
        )

        assertTrue(handled)
        assertEquals(12, liveLevel)
        assertEquals(12, evaluatedLevel)
    }

    @Test fun recoveryBatteryRearmsAlertEvenWhenStandbyFails() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(80, standby = null, checkedAt = 200L)
        )
        var armed = false

        FreshBatteryResultHandler.handle(
            result.freshBatteryLevel,
            updateLiveLevel = {},
            evaluateLowBattery = { level ->
                armed = LowBatteryAlertReducer.reduce(armed, level, threshold = 25).armed
            }
        )

        assertTrue(armed)
        assertTrue(LowBatteryAlertReducer.reduce(armed, 12, threshold = 25).shouldAlert)
    }

    @Test fun standbyQueryOnlyDoesNotAdvanceCompleteSnapshot() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(null, StandbyState.ON, checkedAt = 200L)
        )

        assertEquals(previous, result.snapshot)
        assertFalse(result.completeSnapshotSaved)
    }

    @Test fun confirmedStandbyThenBatteryFailureKeepsNewStateAndOldTimestamp() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.StandbyChange(
                requested = StandbyState.ON,
                finalState = StandbyState.ON,
                verified = true,
                batteryLevel = null,
                checkedAt = 200L
            )
        )

        assertEquals(
            SnapshotState(
                70,
                StandbyState.ON,
                100L,
                SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
            ),
            result.snapshot
        )
        assertTrue(result.standbyChangeConfirmed)
        assertFalse(result.completeSnapshotSaved)
    }

    @Test fun laterCompleteSnapshotClearsBatteryPendingState() {
        val pending = previous.copy(
            standby = StandbyState.ON,
            completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
        )

        val result = SnapshotReducer.reduce(
            pending,
            SnapshotEvent.NormalCheck(82, StandbyState.ON, checkedAt = 300L)
        )

        assertEquals(SnapshotCompleteness.COMPLETE, result.snapshot.completeness)
        assertEquals(82, result.snapshot.batteryLevel)
        assertEquals(300L, result.snapshot.lastChecked)
        assertTrue(result.completeSnapshotSaved)
    }

    @Test fun oppositeFinalStateWithBatteryFailurePersistsActualStateAsPending() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.StandbyChange(
                requested = StandbyState.ON,
                finalState = StandbyState.OFF,
                verified = false,
                batteryLevel = null,
                checkedAt = 200L
            )
        )

        assertEquals(
            previous.copy(
                standby = StandbyState.OFF,
                completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
            ),
            result.snapshot
        )
        assertFalse(result.standbyChangeConfirmed)
        assertFalse(result.completeSnapshotSaved)
    }

    @Test fun oppositeFinalStateWithBatterySuccessSavesCompleteActualSnapshot() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.StandbyChange(
                requested = StandbyState.ON,
                finalState = StandbyState.OFF,
                verified = false,
                batteryLevel = 74,
                checkedAt = 200L
            )
        )

        assertEquals(SnapshotState(74, StandbyState.OFF, 200L), result.snapshot)
        assertTrue(result.completeSnapshotSaved)
        assertFalse(result.standbyChangeConfirmed)
    }

    @Test fun ambiguousStandbyFailurePreservesBatteryAndTimeButHidesOldState() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.StandbyChange(
                requested = StandbyState.ON,
                finalState = null,
                verified = false,
                batteryLevel = null,
                checkedAt = 200L,
                ambiguous = true
            )
        )

        assertEquals(
            SnapshotState(
                batteryLevel = 70,
                standby = StandbyState.UNKNOWN,
                lastChecked = 100L,
                completeness = SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
            ),
            result.snapshot
        )
        assertFalse(result.standbyChangeConfirmed)
        assertFalse(result.completeSnapshotSaved)
    }

    @Test fun laterCompleteCheckClearsAmbiguousAfterCommandState() {
        val ambiguous = previous.copy(
            standby = StandbyState.UNKNOWN,
            completeness = SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
        )

        val result = SnapshotReducer.reduce(
            ambiguous,
            SnapshotEvent.NormalCheck(83, StandbyState.ON, checkedAt = 300L)
        )

        assertEquals(SnapshotState(83, StandbyState.ON, 300L), result.snapshot)
        assertEquals(SnapshotCompleteness.COMPLETE, result.snapshot.completeness)
        assertTrue(result.completeSnapshotSaved)
    }

    @Test fun missingFinalConfirmationDoesNotPersistRequestedStandby() {
        val result = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.StandbyChange(
                requested = StandbyState.ON,
                finalState = null,
                verified = false,
                batteryLevel = null,
                checkedAt = 200L
            )
        )

        assertEquals(StandbyState.OFF, result.snapshot.standby)
        assertEquals(previous, result.snapshot)
        assertFalse(result.standbyChangeConfirmed)
    }

    @Test fun freshPreflightObservationReplacesDisprovedStandbyWithoutAdvancingTime() {
        val updated = snapshotAfterStandbyObservation(previous, StandbyState.ON)

        assertEquals(70, updated.batteryLevel)
        assertEquals(StandbyState.ON, updated.standby)
        assertEquals(100L, updated.lastChecked)
        assertEquals(
            SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING,
            updated.completeness
        )
    }

    @Test fun matchingPreflightObservationLeavesCompleteSnapshotUntouched() {
        assertEquals(previous, snapshotAfterStandbyObservation(previous, StandbyState.OFF))
    }
}
