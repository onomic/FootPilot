package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotPresentationTest {
    private val complete = SnapshotState(
        batteryLevel = 85,
        standby = StandbyState.ON,
        lastChecked = 100L
    )

    @Test fun liveBatteryOverridesSnapshotBatteryOnly() {
        val display = SnapshotPresentation.create(complete, liveBatteryLevel = 79)

        assertEquals("Battery 79%", display.batteryLine)
        assertEquals("Standby on", display.standbyLine)
        assertEquals(100L, display.lastChecked)
        assertEquals("Last checked: 10:20 PM", display.checkedLine("10:20 PM"))
        assertEquals(85, complete.batteryLevel)
        assertEquals(100L, complete.lastChecked)
    }

    @Test fun nullLiveBatteryFallsBackToCompleteSnapshotBattery() {
        val display = SnapshotPresentation.create(complete, liveBatteryLevel = null)

        assertEquals("Battery 85%", display.batteryLine)
        assertEquals(100L, display.lastChecked)
    }

    @Test fun pendingDisplayPreservesConfirmedStandbyActionAndShowsWarning() {
        val pending = complete.copy(
            completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
        )

        val display = SnapshotPresentation.create(pending)

        assertEquals(StandbyState.ON, display.standby)
        assertEquals("Standby on", display.standbyLine)
        assertEquals("Last complete check: 10:20 PM", display.checkedLine("10:20 PM"))
        assertEquals("Battery not verified after standby change", display.verificationMessage)
    }

    @Test fun completeDisplayHasNoVerificationWarning() {
        assertNull(SnapshotPresentation.create(complete).verificationMessage)
    }
}
