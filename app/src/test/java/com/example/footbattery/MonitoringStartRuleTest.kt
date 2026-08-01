package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringStartRuleTest {
    @Test fun startRequiresBluetoothAndAnIdleStoppedOwner() {
        assertTrue(canStartMonitoring(running = false, busy = false, bluetoothAvailable = true))
        assertFalse(canStartMonitoring(running = false, busy = false, bluetoothAvailable = false))
        assertFalse(canStartMonitoring(running = true, busy = false, bluetoothAvailable = true))
        assertFalse(canStartMonitoring(running = false, busy = true, bluetoothAvailable = true))
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
