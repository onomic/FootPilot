package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStateResetTest {
    @Test fun resetClearsFootStateAndPreservesUserSettings() {
        val mutation = deviceStateResetMutation()
        val input = buildMap<String, Any?> {
            mutation.removedKeys.forEach { put(it, "old-foot-value") }
            put(FootPreferenceKeys.ARMED, false)
            put(FootPreferenceKeys.POLLING, true)
            put(FootPreferenceKeys.MONITORING, true)
            put(FootPreferenceKeys.THRESHOLD, 35)
            put(FootPreferenceKeys.PAIRING_CODE, "123456")
            put(FootPreferenceKeys.INTERVAL_MIN, 120)
            put(FootPreferenceKeys.SELECTED_FOOT_NAME, "NewFoot")
            put(FootPreferenceKeys.SELECTED_FOOT_ADDRESS, "AA:BB:CC:DD:EE:FF")
        }

        val output = mutation.applyTo(input)

        mutation.removedKeys.forEach { assertFalse("$it retained old-foot data", output.containsKey(it)) }
        assertEquals(true, output[FootPreferenceKeys.ARMED])
        assertEquals(false, output[FootPreferenceKeys.POLLING])
        assertEquals(false, output[FootPreferenceKeys.MONITORING])
        assertEquals(35, output[FootPreferenceKeys.THRESHOLD])
        assertEquals("123456", output[FootPreferenceKeys.PAIRING_CODE])
        assertEquals(120, output[FootPreferenceKeys.INTERVAL_MIN])
        assertEquals("NewFoot", output[FootPreferenceKeys.SELECTED_FOOT_NAME])
        assertEquals("AA:BB:CC:DD:EE:FF", output[FootPreferenceKeys.SELECTED_FOOT_ADDRESS])
        assertTrue(mutation.removedKeys.contains(FootPreferenceKeys.SNAPSHOT_COMPLETENESS))
        assertTrue(mutation.removedKeys.contains(FootPreferenceKeys.ANKLE_CERTAINTY))
        assertTrue(mutation.removedKeys.contains(FootPreferenceKeys.PRESET_BOOTS_MD))
    }
}
