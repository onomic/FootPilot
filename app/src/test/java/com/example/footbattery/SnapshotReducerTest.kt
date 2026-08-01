package com.example.footbattery

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
        assertFalse(result.completeSnapshotSaved)
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

        assertEquals(SnapshotState(70, StandbyState.ON, 100L), result.snapshot)
        assertTrue(result.standbyChangeConfirmed)
        assertFalse(result.completeSnapshotSaved)
    }

    @Test fun failedStandbyConfirmationDoesNotClaimSuccess() {
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

        assertEquals(previous, result.snapshot)
        assertFalse(result.standbyChangeConfirmed)
        assertFalse(result.completeSnapshotSaved)
    }
}
