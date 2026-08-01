package com.example.footbattery

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
}
