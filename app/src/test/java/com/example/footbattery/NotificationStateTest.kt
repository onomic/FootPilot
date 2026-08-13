package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStateTest {
    private val snapshot = SnapshotState(
        batteryLevel = 80,
        standby = StandbyState.OFF,
        lastChecked = 100L
    )

    @Test fun transientResultRemainsVisibleDuringLiveBatteryRefresh() {
        val store = TransientStatusStore(durationMs = 8_000L)
        store.replace("Battery check failed", nowMs = 1_000L)

        val model = model(store, nowMs = 2_000L, liveBattery = 79)

        assertEquals("Battery check failed", model.statusText)
    }

    @Test fun liveBatteryRefreshUpdatesBatteryWithoutReplacingTransient() {
        val store = TransientStatusStore(durationMs = 8_000L)
        store.replace("Foot remained standby off", nowMs = 1_000L)

        val first = model(store, nowMs = 2_000L, liveBattery = 79)
        val refreshed = model(store, nowMs = 3_000L, liveBattery = 76)

        assertEquals("Battery 79%", first.display.batteryLine)
        assertEquals("Battery 76%", refreshed.display.batteryLine)
        assertEquals("Foot remained standby off", refreshed.statusText)
    }

    @Test fun expandedContentUsesBatteryAsTitleWithoutRepeatingItInDetails() {
        val onSnapshot = snapshot.copy(batteryLevel = 85, standby = StandbyState.ON)

        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(onSnapshot),
            formattedTime = "2:34 a.m.",
            statusText = null
        )

        assertEquals("Battery 85%", content.title)
        assertEquals(
            listOf("Standby on", "Ankle angle unknown", "Last checked: 2:34 a.m."),
            content.expandedLines
        )
        assertFalse(content.expandedLines.contains("Battery 85%"))
    }

    @Test fun expandedContentShowsVerificationWarningOnce() {
        val pending = snapshot.copy(
            batteryLevel = 85,
            standby = StandbyState.ON,
            completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
        )
        val warning = "Battery not verified after standby change"

        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(pending),
            formattedTime = "2:34 a.m.",
            statusText = "  $warning  "
        )

        assertEquals(
            listOf(
                "Standby on",
                "Ankle angle unknown",
                "Last complete check: 2:34 a.m.",
                warning
            ),
            content.expandedLines
        )
        assertEquals(1, content.expandedLines.count { it == warning })
    }

    @Test fun expandedContentShowsTransientStatusOnceAfterSnapshotDetails() {
        val transient = "Standby on confirmed"

        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(
                snapshot.copy(batteryLevel = 85, standby = StandbyState.ON)
            ),
            formattedTime = "2:34 a.m.",
            statusText = transient
        )

        assertEquals(
            listOf(
                "Standby on",
                "Ankle angle unknown",
                "Last checked: 2:34 a.m.",
                transient
            ),
            content.expandedLines
        )
        assertEquals(1, content.expandedLines.count { it == transient })
    }

    @Test fun transientDisappearsAfterExpiration() {
        val store = TransientStatusStore(durationMs = 8_000L)
        val token = store.replace("Bluetooth transaction timed out", nowMs = 1_000L)

        assertEquals("Bluetooth transaction timed out", store.visibleText(8_999L))
        assertTrue(store.expire(token, 9_000L))
        assertNull(store.visibleText(9_000L))
    }

    @Test fun olderExpirationCannotClearNewerTransient() {
        val store = TransientStatusStore(durationMs = 8_000L)
        val older = store.replace("Older result", nowMs = 1_000L)
        store.replace("Newer result", nowMs = 2_000L)

        assertFalse(store.expire(older, nowMs = 9_000L))
        assertEquals("Newer result", store.visibleText(9_000L))
    }

    @Test fun activeOperationTextTakesPrecedenceOverTransient() {
        val model = NotificationStatePresentation.create(
            snapshot = snapshot,
            liveBatteryLevel = 75,
            activeOperationText = "Checking...",
            transientText = "Battery check failed",
            actionsSafe = true
        )

        assertEquals("Checking...", model.statusText)
    }

    @Test fun liveBatteryRefreshNeverTargetsPollingNotification() {
        val monitoring = liveBatteryRefreshPlan(monitoringRequested = true)
        val stopped = liveBatteryRefreshPlan(monitoringRequested = false)

        assertTrue(monitoring.refreshOngoing)
        assertFalse(monitoring.refreshPolling)
        assertFalse(stopped.refreshOngoing)
        assertFalse(stopped.refreshPolling)
    }

    @Test fun actionsAreSuppressedDuringActiveOperation() {
        val model = NotificationStatePresentation.create(
            snapshot = snapshot,
            liveBatteryLevel = 75,
            activeOperationText = "Turning standby on...",
            transientText = "Older result",
            actionsSafe = true
        )

        assertFalse(model.includeActions)
        assertEquals(
            emptyList<StateNotificationAction>(),
            stateNotificationActions(model.display, confirmedAnkle(), false)
        )
    }

    @Test fun ambiguousNotificationKeepsCheckNowAndOmitsStandbyAction() {
        val ambiguous = snapshot.copy(
            standby = StandbyState.UNKNOWN,
            completeness = SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
        )
        val display = SnapshotPresentation.create(ambiguous)

        assertEquals(
            listOf(StateNotificationAction.CHECK_NOW),
            stateNotificationActions(display, confirmedAnkle(), includeActions = true)
        )
    }

    @Test fun actionsReturnAfterOperationAndTransientLifecycle() {
        val store = TransientStatusStore(durationMs = 8_000L)
        store.replace("Older result", nowMs = 0L)
        store.beginOperation()
        assertNull(store.visibleText(1_000L))

        val token = store.replace("Standby on confirmed", nowMs = 2_000L)
        val resultModel = model(store, nowMs = 3_000L, liveBattery = 78)
        assertEquals("Standby on confirmed", resultModel.statusText)
        assertTrue(resultModel.includeActions)
        assertEquals(
            listOf(
                StateNotificationAction.CHECK_NOW,
                StateNotificationAction.STANDBY,
                StateNotificationAction.AUTO
            ),
            stateNotificationActions(
                resultModel.display,
                confirmedAnkle(),
                resultModel.includeActions
            )
        )

        assertTrue(store.expire(token, nowMs = 10_000L))
        val normalModel = model(store, nowMs = 10_000L, liveBattery = 78)
        assertNull(normalModel.statusText)
        assertTrue(normalModel.includeActions)
    }

    @Test fun collapsedKnownStateIncludesExactPresetMatchAndSignedAngle() {
        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(snapshot.copy(batteryLevel = 93)),
            ankle = confirmedAnkle(1800),
            presets = PresetState(PresetTargets(runningMd = 1800), FootwearPreset.RUNNING),
            formattedTime = "2:34 a.m.",
            statusText = null
        )

        assertEquals("Battery 93%", content.title)
        assertEquals("Battery", content.batteryLabel)
        assertEquals("93%", content.batteryValue)
        assertEquals("Standby off · Running shoes · +1.8°", content.collapsedText)
        assertEquals("Running shoes · Confirmed +1.8°", content.angleSummaryText)
        assertEquals("Running shoes", content.summaryPresetName)
        assertEquals("Confirmed +1.8°", content.angleStatusText)
        assertTrue(content.angleStatusConfirmed)
        assertFalse(
            listOf(
                content.collapsedText,
                content.angleSummaryText,
                content.summaryPresetName,
                content.angleStatusText
            ).filterNotNull().any { "✓" in it }
        )
    }

    @Test fun collapsedKnownStateWithoutMatchNeverInventsCustom() {
        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(snapshot),
            ankle = confirmedAnkle(569),
            presets = PresetState(PresetTargets(runningMd = 1800)),
            formattedTime = null,
            statusText = null
        )

        assertEquals("Standby off · +0.6°", content.collapsedText)
        assertFalse(content.collapsedText.contains("Custom"))
    }

    @Test fun standbyOnLabelsRetainedAngleAsLastVerified() {
        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(snapshot.copy(standby = StandbyState.ON)),
            ankle = confirmedAnkle(1800),
            presets = PresetState(PresetTargets(runningMd = 1800), FootwearPreset.RUNNING),
            formattedTime = null,
            statusText = null
        )

        assertEquals("Standby on · Last verified +1.8°", content.collapsedText)
        assertEquals("Last verified +1.8°", content.angleSummaryText)
        assertNull(content.summaryPreset)
        assertEquals(
            listOf(StateNotificationAction.CHECK_NOW, StateNotificationAction.STANDBY),
            stateNotificationActions(
                SnapshotPresentation.create(snapshot.copy(standby = StandbyState.ON)),
                confirmedAnkle(1800),
                includeActions = true
            )
        )
    }

    @Test fun normalSafeActionOrderIsExactlyCheckStandbyAuto() {
        assertEquals(
            listOf(
                StateNotificationAction.CHECK_NOW,
                StateNotificationAction.STANDBY,
                StateNotificationAction.AUTO
            ),
            stateNotificationActions(
                SnapshotPresentation.create(snapshot),
                confirmedAnkle(),
                includeActions = true
            )
        )
    }

    @Test fun unknownAfterCommandNeverPresentsCachedAngleAsCurrent() {
        val ankle = AnkleState(
            lastVerifiedMd = 1800,
            certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND
        )
        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(snapshot),
            ankle = ankle,
            formattedTime = null,
            statusText = null
        )

        assertEquals("Standby off", content.collapsedText)
        assertEquals("Unknown · Last verified +1.8°", content.angleSummaryText)
        assertFalse(content.angleSummaryText.contains("Confirmed"))
    }

    @Test fun failedFreshQueryLabelsPersistedAngleOnlyAsHistory() {
        val ankle = AnkleState(
            lastVerifiedMd = 1800,
            certainty = AnkleCertainty.UNKNOWN
        )
        val content = StateNotificationContentPresentation.create(
            display = SnapshotPresentation.create(snapshot),
            ankle = ankle,
            formattedTime = null,
            statusText = null
        )

        assertEquals("Standby off · Ankle unknown", content.collapsedText)
        assertEquals("Unknown · Last verified +1.8°", content.angleSummaryText)
        assertFalse(content.angleSummaryText.contains("Confirmed"))
        assertEquals(
            listOf(StateNotificationAction.CHECK_NOW, StateNotificationAction.STANDBY),
            stateNotificationActions(
                SnapshotPresentation.create(snapshot),
                ankle,
                includeActions = true
            )
        )
    }

    @Test fun presetTargetsRequireConfiguredSafeConfirmedState() {
        val presets = PresetState(PresetTargets(runningMd = 1800, bootsMd = 3000))
        val display = SnapshotPresentation.create(snapshot)

        assertEquals(
            setOf(FootwearPreset.RUNNING, FootwearPreset.BOOTS),
            notificationPresetActions(display, confirmedAnkle(), presets, includeActions = true)
        )
        assertEquals(
            emptySet<FootwearPreset>(),
            notificationPresetActions(
                display,
                AnkleState(certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND),
                presets,
                includeActions = true
            )
        )
    }

    @Test fun activePresetLabelHasNoCheckmarkAndUnavailableStateIsMuted() {
        val active = notificationPresetPresentation(
            preset = FootwearPreset.RUNNING,
            physicallyActive = true,
            actionable = true
        )
        val blocked = notificationPresetPresentation(
            preset = FootwearPreset.RUNNING,
            physicallyActive = true,
            actionable = false
        )

        assertEquals("Running", active.label)
        assertFalse(active.label.contains("✓"))
        assertEquals(NotificationPresetVisualState.ACTIVE_ACTIONABLE, active.visualState)
        assertEquals(NotificationPresetVisualState.ACTIVE_UNAVAILABLE, blocked.visualState)
        assertTrue(blocked.cellAlpha < active.cellAlpha)
        assertTrue(blocked.imageAlpha < active.imageAlpha)
    }

    private fun confirmedAnkle(md: Int = 1800) = AnkleState(
        lastVerifiedMd = md,
        certainty = AnkleCertainty.CONFIRMED
    )

    private fun model(
        store: TransientStatusStore,
        nowMs: Long,
        liveBattery: Int
    ): StateNotificationModel = NotificationStatePresentation.create(
        snapshot = snapshot,
        liveBatteryLevel = liveBattery,
        activeOperationText = null,
        transientText = store.visibleText(nowMs),
        actionsSafe = true
    )
}
