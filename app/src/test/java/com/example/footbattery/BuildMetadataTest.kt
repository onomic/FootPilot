package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildMetadataTest {
    @Test fun physicalValidationBuildUsesBetaVersionMetadata() {
        assertEquals("1.2.0-beta2", BuildConfig.VERSION_NAME)
        assertEquals(3, BuildConfig.VERSION_CODE)
    }
}
