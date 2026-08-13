package com.example.footbattery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ShoeHeightCalibrationTest {
    @Test fun missingCalibrationReturnsNoAbsoluteTargetForEveryApprovedHeight() {
        assertFalse(UnconfiguredShoeHeightCalibration.configured)
        ShoeHeightChange.APPROVED_V1.forEach { change ->
            assertNull(UnconfiguredShoeHeightCalibration.targetFor(4499, change))
        }
    }
}
