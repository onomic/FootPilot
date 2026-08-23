package com.onomic.footpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringStartRuleTest {
    @Test fun startRequiresBluetoothAndAnIdleStoppedOwner() {
        assertTrue(canStartMonitoring(false, false, bluetoothAvailable = true, footSelected = true))
        assertFalse(canStartMonitoring(false, false, bluetoothAvailable = false, footSelected = true))
        assertFalse(canStartMonitoring(true, false, bluetoothAvailable = true, footSelected = true))
        assertFalse(canStartMonitoring(false, true, bluetoothAvailable = true, footSelected = true))
        assertFalse(canStartMonitoring(false, false, bluetoothAvailable = true, footSelected = false))
    }

    @Test fun unavailableWhileIdleShowsUnavailableStatus() {
        assertEquals(
            "Turn on Bluetooth",
            bluetoothAvailabilityStatus(
                currentStatus = "Idle",
                bluetoothAvailable = false,
                monitoring = false,
                unavailableStatus = "Turn on Bluetooth"
            )
        )
    }

    @Test fun becomingAvailableClearsTurnOnBluetoothStatus() {
        assertEquals(
            "Idle",
            bluetoothAvailabilityStatus(
                currentStatus = "Turn on Bluetooth",
                bluetoothAvailable = true,
                monitoring = false,
                unavailableStatus = "Turn on Bluetooth"
            )
        )
    }

    @Test fun becomingAvailableClearsPermissionRequiredStatus() {
        assertEquals(
            "Idle",
            bluetoothAvailabilityStatus(
                currentStatus = "Bluetooth permission is required",
                bluetoothAvailable = true,
                monitoring = false,
                unavailableStatus = "Bluetooth permission is required"
            )
        )
    }

    @Test fun becomingAvailablePreservesRealOperationFailure() {
        assertEquals(
            "Standby change failed",
            bluetoothAvailabilityStatus(
                currentStatus = "Standby change failed",
                bluetoothAvailable = true,
                monitoring = false,
                unavailableStatus = "Turn on Bluetooth"
            )
        )
    }

    @Test fun monitoringStatusIsNotIncorrectlyReplacedWithIdle() {
        assertEquals(
            "Monitoring",
            bluetoothAvailabilityStatus(
                currentStatus = "Monitoring",
                bluetoothAvailable = true,
                monitoring = true,
                unavailableStatus = "Turn on Bluetooth"
            )
        )
    }
}
