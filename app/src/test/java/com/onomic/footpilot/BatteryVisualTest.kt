package com.onomic.footpilot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryVisualTest {
    @Test fun semanticBoundariesMatchTheFixedVisualBands() {
        val expected = linkedMapOf(
            null to BatteryVisualBand.UNKNOWN,
            0 to BatteryVisualBand.CRITICAL,
            15 to BatteryVisualBand.CRITICAL,
            16 to BatteryVisualBand.WARNING,
            17 to BatteryVisualBand.WARNING,
            35 to BatteryVisualBand.WARNING,
            36 to BatteryVisualBand.NORMAL,
            100 to BatteryVisualBand.NORMAL
        )

        expected.forEach { (level, band) ->
            assertEquals("level=$level", band, batteryVisualBand(level))
        }
    }

    @Test fun notificationBandsMapToTheRequiredResources() {
        assertEquals(R.color.footbattery_icon_neutral, notificationBatteryColorResource(null))
        assertEquals(R.color.footbattery_critical_notification, notificationBatteryColorResource(12))
        assertEquals(R.color.footbattery_warning_notification, notificationBatteryColorResource(17))
        assertEquals(R.color.footbattery_warning_notification, notificationBatteryColorResource(35))
        assertEquals(R.color.footbattery_green_notification, notificationBatteryColorResource(36))
        assertEquals(R.color.footbattery_green_notification, notificationBatteryColorResource(80))
    }

    @Test fun notificationContentCarriesTheExactResolvedLevelItRenders() {
        val snapshot = SnapshotState(80, StandbyState.OFF, 100L)
        val liveContent = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(snapshot, liveBatteryLevel = 17),
            formattedTime = null,
            statusText = null
        )
        val pollingContent = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(snapshot.copy(batteryLevel = 36)),
            formattedTime = null,
            statusText = null
        )

        assertEquals("17%", liveContent.batteryValue)
        assertEquals(17, liveContent.batteryLevel)
        assertEquals(
            R.color.footbattery_warning_notification,
            notificationBatteryColorResource(liveContent.batteryLevel)
        )
        assertEquals("36%", pollingContent.batteryValue)
        assertEquals(36, pollingContent.batteryLevel)
        assertEquals(
            R.color.footbattery_green_notification,
            notificationBatteryColorResource(pollingContent.batteryLevel)
        )
    }

    @Test fun allThreeNumericViewsUseTheAdaptiveColorHelperAndLabelsDoNot() {
        val source = source("app/src/main/java/com/onomic/footpilot/Alerts.kt")
        val valueIds = listOf(
            "notification_collapsed_battery_value",
            "notification_expanded_battery_value",
            "notification_auto_battery_value"
        )
        valueIds.forEach { id ->
            assertTrue(
                Regex("setBatteryValueColor\\s*\\(\\s*ctx,\\s*R\\.id\\.$id")
                    .containsMatchIn(source)
            )
        }
        listOf(
            "notification_collapsed_battery_label",
            "notification_expanded_battery_label",
            "notification_auto_battery_label"
        ).forEach { id ->
            assertFalse(
                Regex("setBatteryValueColor\\s*\\([^)]*R\\.id\\.$id")
                    .containsMatchIn(source)
            )
        }
        assertTrue(source.contains("setAdaptiveColorResource("))
        assertTrue(source.contains("setDeferredColorResource("))
    }

    @Test fun appAccentUsesTheSameSemanticClassifier() {
        val source = source("app/src/main/java/com/onomic/footpilot/MainActivity.kt")
        val colorFunction = source.substringAfter("private fun colorForLevel")
            .substringBefore("class MainActivity")

        assertTrue(colorFunction.contains("batteryVisualBand(level)"))
        BatteryVisualBand.entries.forEach { band ->
            assertTrue(colorFunction.contains("BatteryVisualBand.${band.name}"))
        }
    }

    @Test fun requiredNotificationColorValuesAreExactAndNormalGreenIsRetained() {
        val colors = source("app/src/main/res/values/colors.xml")

        assertTrue(colors.contains("footbattery_green_notification\">#0B7A1D"))
        assertTrue(colors.contains("footbattery_warning_notification\">#F5B94A"))
        assertTrue(colors.contains("footbattery_critical_notification\">#F0604D"))
    }

    @Test fun standbyNotificationWaitTextIsStableAcrossCountdownTicks() {
        assertEquals("Retrying standby...", standbyRetryNotificationText(15))
        assertEquals("Retrying standby...", standbyRetryNotificationText(1))
        assertEquals(null, standbyRetryNotificationText(null))
        assertEquals(null, standbyRetryNotificationText(0))
    }

    private fun source(path: String): String {
        val candidates = listOf(File(path), File("../$path"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not locate $path from ${File(".").absolutePath}"
        }.readText()
    }
}
