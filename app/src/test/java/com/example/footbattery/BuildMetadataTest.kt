package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildMetadataTest {
    @Test fun physicalValidationBuildUsesBetaVersionMetadata() {
        assertEquals("1.2.0-beta3", BuildConfig.VERSION_NAME)
        assertEquals(4, BuildConfig.VERSION_CODE)
        assertEquals("com.example.footbattery", BuildConfig.APPLICATION_ID)
    }

    @Test fun visibleApplicationNameIsFootPilot() {
        val stringsFile = listOf(
            File("app/src/main/res/values/strings.xml"),
            File("src/main/res/values/strings.xml")
        ).first { it.isFile }
        assertTrue(stringsFile.readText().contains("<string name=\"app_name\">FootPilot</string>"))
    }
}
