package com.onomic.footpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildMetadataTest {
    @Test fun physicalValidationBuildUsesBetaVersionMetadata() {
        assertEquals("1.3.0-beta1", BuildConfig.VERSION_NAME)
        assertEquals(5, BuildConfig.VERSION_CODE)
        assertEquals("com.onomic.footpilot", BuildConfig.APPLICATION_ID)
    }

    @Test fun visibleApplicationNameIsFootPilot() {
        val stringsFile = listOf(
            File("app/src/main/res/values/strings.xml"),
            File("src/main/res/values/strings.xml")
        ).first { it.isFile }
        assertTrue(stringsFile.readText().contains("<string name=\"app_name\">FootPilot</string>"))
    }

    @Test fun notificationActionsUseFootPilotApplicationIdentity() {
        assertEquals("com.onomic.footpilot.CHECK_NOW", Alerts.ACTION_CHECK_NOW)
        assertEquals("com.onomic.footpilot.STANDBY", Alerts.ACTION_STANDBY)
        assertEquals("com.onomic.footpilot.AUTO_ALIGN", Alerts.ACTION_AUTO)
        assertEquals("com.onomic.footpilot.PRESET_BAREFOOT", Alerts.ACTION_PRESET_BAREFOOT)
        assertEquals("com.onomic.footpilot.PRESET_RUNNING", Alerts.ACTION_PRESET_RUNNING)
        assertEquals("com.onomic.footpilot.PRESET_DRESS", Alerts.ACTION_PRESET_DRESS)
        assertEquals("com.onomic.footpilot.PRESET_BOOTS", Alerts.ACTION_PRESET_BOOTS)
    }
}
