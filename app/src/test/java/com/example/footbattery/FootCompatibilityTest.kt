package com.example.footbattery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootCompatibilityTest {
    private val complete = FootGattProfilePresence(
        batteryService = true,
        batteryLevel = true,
        ossurService = true,
        aa01 = true,
        aa02 = true
    )

    @Test fun completeRequiredProfileIsCompatible() {
        assertTrue(isCompatibleFootProfile(complete))
    }

    @Test fun everyRequiredServiceAndCharacteristicIsMandatory() {
        assertFalse(isCompatibleFootProfile(complete.copy(batteryService = false)))
        assertFalse(isCompatibleFootProfile(complete.copy(batteryLevel = false)))
        assertFalse(isCompatibleFootProfile(complete.copy(ossurService = false)))
        assertFalse(isCompatibleFootProfile(complete.copy(aa01 = false)))
        assertFalse(isCompatibleFootProfile(complete.copy(aa02 = false)))
    }
}
