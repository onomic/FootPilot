package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenPresentationTest {
    @Test fun compactAvailableHeightUsesCompactGeometry() {
        assertEquals(
            MainScreenHeightClass.COMPACT,
            mainScreenLayoutSpec(availableHeightDp = 640f, fontScale = 1f).heightClass
        )
    }

    @Test fun regularAvailableHeightUsesRegularGeometry() {
        assertEquals(
            MainScreenHeightClass.REGULAR,
            mainScreenLayoutSpec(availableHeightDp = 760f, fontScale = 1f).heightClass
        )
    }

    @Test fun tallAvailableHeightUsesTallGeometry() {
        assertEquals(
            MainScreenHeightClass.TALL,
            mainScreenLayoutSpec(availableHeightDp = 900f, fontScale = 1f).heightClass
        )
    }

    @Test fun increasedFontScaleMovesBorderlineRegularHeightToCompact() {
        assertEquals(
            MainScreenHeightClass.REGULAR,
            mainScreenLayoutSpec(availableHeightDp = 760f, fontScale = 1f).heightClass
        )
        assertEquals(
            MainScreenHeightClass.COMPACT,
            mainScreenLayoutSpec(availableHeightDp = 760f, fontScale = 1.2f).heightClass
        )
    }

    @Test fun heightClassBoundariesAreDeterministic() {
        assertEquals(
            MainScreenHeightClass.COMPACT,
            mainScreenLayoutSpec(availableHeightDp = 699.99f, fontScale = 1f).heightClass
        )
        assertEquals(
            MainScreenHeightClass.REGULAR,
            mainScreenLayoutSpec(availableHeightDp = 700f, fontScale = 1f).heightClass
        )
        assertEquals(
            MainScreenHeightClass.REGULAR,
            mainScreenLayoutSpec(availableHeightDp = 819.99f, fontScale = 1f).heightClass
        )
        val tallBoundary = mainScreenLayoutSpec(availableHeightDp = 820f, fontScale = 1f)
        assertEquals(MainScreenHeightClass.TALL, tallBoundary.heightClass)
        assertEquals(tallBoundary, mainScreenLayoutSpec(availableHeightDp = 820f, fontScale = 1f))
    }

    @Test fun heightClassesExposeTheExpectedGaugeAndFootControlSizes() {
        val compact = mainScreenLayoutSpec(640f, 1f)
        val regular = mainScreenLayoutSpec(760f, 1f)
        val tall = mainScreenLayoutSpec(900f, 1f)

        assertEquals(132f, compact.gaugeSizeDp, 0f)
        assertEquals(126f, compact.footControlsMinHeightDp, 0f)
        assertEquals(148f, regular.gaugeSizeDp, 0f)
        assertEquals(130f, regular.footControlsMinHeightDp, 0f)
        assertEquals(168f, tall.gaugeSizeDp, 0f)
        assertEquals(134f, tall.footControlsMinHeightDp, 0f)
    }

    @Test fun heightClassesExposeCenteredHierarchySpacing() {
        val compact = mainScreenLayoutSpec(640f, 1f)
        val regular = mainScreenLayoutSpec(760f, 1f)
        val tall = mainScreenLayoutSpec(900f, 1f)

        assertEquals(6f, compact.gaugeToDeviceGapDp, 0f)
        assertEquals(7f, regular.gaugeToDeviceGapDp, 0f)
        assertEquals(8f, tall.gaugeToDeviceGapDp, 0f)
        assertEquals(4f, compact.deviceToThresholdGapDp, 0f)
        assertEquals(4f, regular.deviceToThresholdGapDp, 0f)
        assertEquals(4f, tall.deviceToThresholdGapDp, 0f)
        assertEquals(10f, compact.metadataToCardGapDp, 0f)
        assertEquals(12f, regular.metadataToCardGapDp, 0f)
        assertEquals(14f, tall.metadataToCardGapDp, 0f)
        assertEquals(12f, compact.cardToCardGapDp, 0f)
        assertEquals(14f, regular.cardToCardGapDp, 0f)
        assertEquals(16f, tall.cardToCardGapDp, 0f)
        assertEquals(8f, compact.cardToStatusGapDp, 0f)
        assertEquals(8f, regular.cardToStatusGapDp, 0f)
        assertEquals(10f, tall.cardToStatusGapDp, 0f)

        listOf(compact, regular, tall).forEach { layout ->
            assertTrue(layout.metadataToCardGapDp > 0f)
            assertTrue(layout.cardToCardGapDp > layout.cardToStatusGapDp)
            assertTrue(layout.cardToStatusGapDp > 0f)
        }
    }

    @Test fun readyRunningConnectionResolvesToConnected() {
        val presentation = mainScreenModePresentation(
            running = true,
            connectionState = LiveConnectionState.READY,
            pollingEnabled = false
        )

        assertEquals(MainScreenMode.CONNECTED, presentation.mode)
        assertEquals("CONNECTED", presentation.label)
    }

    @Test fun connectedPresentationPreservesTheLivePulse() {
        assertTrue(
            mainScreenModePresentation(
                running = true,
                connectionState = LiveConnectionState.READY,
                pollingEnabled = false
            ).pulses
        )
    }

    @Test fun connectingRunningConnectionResolvesToConnecting() {
        assertEquals(
            MainScreenMode.CONNECTING,
            mainScreenModePresentation(
                running = true,
                connectionState = LiveConnectionState.CONNECTING,
                pollingEnabled = false
            ).mode
        )
    }

    @Test fun discoveringRunningConnectionResolvesToConnecting() {
        assertEquals(
            MainScreenMode.CONNECTING,
            mainScreenModePresentation(
                running = true,
                connectionState = LiveConnectionState.DISCOVERING,
                pollingEnabled = false
            ).mode
        )
    }

    @Test fun initializingRunningConnectionResolvesToConnecting() {
        assertEquals(
            MainScreenMode.CONNECTING,
            mainScreenModePresentation(
                running = true,
                connectionState = LiveConnectionState.INITIALIZING,
                pollingEnabled = false
            ).mode
        )
    }

    @Test fun failedRunningConnectionResolvesToConnecting() {
        val presentation = mainScreenModePresentation(
            running = true,
            connectionState = LiveConnectionState.FAILED,
            pollingEnabled = false
        )

        assertEquals(MainScreenMode.CONNECTING, presentation.mode)
        assertEquals("CONNECTING", presentation.label)
        assertTrue(presentation.pulses)
    }

    @Test fun connectingPresentationPulses() {
        assertTrue(
            mainScreenModePresentation(
                running = true,
                connectionState = LiveConnectionState.CONNECTING,
                pollingEnabled = false
            ).pulses
        )
    }

    @Test fun disconnectingConnectionResolvesToDisconnecting() {
        val presentation = mainScreenModePresentation(
            running = true,
            connectionState = LiveConnectionState.DISCONNECTING,
            pollingEnabled = true
        )

        assertEquals(MainScreenMode.DISCONNECTING, presentation.mode)
        assertEquals("DISCONNECTING", presentation.label)
        assertFalse(presentation.usesActiveColor)
        assertFalse(presentation.pulses)
    }

    @Test fun stoppedConnectionWithPollingResolvesToPolling() {
        val presentation = mainScreenModePresentation(
            running = false,
            connectionState = LiveConnectionState.IDLE,
            pollingEnabled = true
        )

        assertEquals(MainScreenMode.POLLING, presentation.mode)
        assertEquals("POLLING", presentation.label)
        assertTrue(presentation.usesActiveColor)
        assertFalse(presentation.pulses)
    }

    @Test fun stoppedConnectionWithoutPollingResolvesToIdle() {
        val presentation = mainScreenModePresentation(
            running = false,
            connectionState = LiveConnectionState.IDLE,
            pollingEnabled = false
        )

        assertEquals(MainScreenMode.IDLE, presentation.mode)
        assertEquals("IDLE", presentation.label)
        assertFalse(presentation.usesActiveColor)
        assertFalse(presentation.pulses)
    }

    @Test fun liveRequestTakesPriorityOverPolling() {
        assertEquals(
            MainScreenMode.CONNECTING,
            mainScreenModePresentation(
                running = true,
                connectionState = LiveConnectionState.INITIALIZING,
                pollingEnabled = true
            ).mode
        )
    }

    @Test fun connectedAndConnectingModesUseActiveColorMetadata() {
        assertTrue(
            mainScreenModePresentation(true, LiveConnectionState.READY, false).usesActiveColor
        )
        assertTrue(
            mainScreenModePresentation(true, LiveConnectionState.FAILED, false).usesActiveColor
        )
    }

    @Test fun activeOperationOverridesVerificationAndOtherStatuses() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = "Turning standby on...",
            verificationMessage = "Battery not verified after standby change",
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring",
            retrySecondsRemaining = 15
        )

        assertEquals("Turning standby on...", presentation.statusText)
        assertEquals(MainScreenStatusKind.ACTIVE_OPERATION, presentation.statusKind)
    }

    @Test fun retryCountdownOverridesVerificationStandbyAndGeneralStatuses() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = "Ankle angle could not be verified",
            standbyStatus = "Checking standby...",
            generalStatus = "Bluetooth connection failed",
            retrySecondsRemaining = 15
        )

        assertEquals("Retrying in 15s...", presentation.statusText)
        assertEquals(MainScreenStatusKind.RETRY_WAIT, presentation.statusKind)
    }

    @Test fun retryCountdownFormatsWholeSecondsExactly() {
        assertEquals("Retrying in 15s...", liveRetryStatusText(15))
        assertEquals("Retrying in 1s...", liveRetryStatusText(1))
    }

    @Test fun standbyRetryUsesDistinctWordingAndTheSameStatusPriority() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = "Verification warning",
            standbyStatus = "Standby status",
            generalStatus = "General status",
            standbyRetrySecondsRemaining = BleRetryPolicy.retryDelaySeconds
        )

        assertEquals("Retrying standby in 15s...", presentation.statusText)
        assertEquals(MainScreenStatusKind.RETRY_WAIT, presentation.statusKind)
        assertEquals("Retrying standby in 1s...", standbyRetryStatusText(1))
    }

    @Test fun activeOperationOutranksStandbyRetryAndOnlyOneCountdownIsRendered() {
        val active = MainScreenPresentation.create(
            activeOperationText = "Turning standby off...",
            verificationMessage = null,
            standbyStatus = null,
            generalStatus = null,
            retrySecondsRemaining = 9,
            standbyRetrySecondsRemaining = 8
        )
        val overlappingWaits = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = null,
            generalStatus = null,
            retrySecondsRemaining = 9,
            standbyRetrySecondsRemaining = 8
        )

        assertEquals("Turning standby off...", active.statusText)
        assertEquals("Retrying standby in 8s...", overlappingWaits.statusText)
        assertFalse(overlappingWaits.statusText.contains("Retrying in 9s"))
    }

    @Test fun nullRetryCountdownIsAbsentAndKeepsExistingPriority() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = "Verification warning",
            standbyStatus = "Standby status",
            generalStatus = "General status",
            retrySecondsRemaining = null
        )

        assertEquals("Verification warning", presentation.statusText)
        assertEquals(MainScreenStatusKind.VERIFICATION_WARNING, presentation.statusKind)
        assertEquals(null, liveRetryStatusText(null))
    }

    @Test fun nonPositiveRetrySecondsAreNotPresented() {
        assertEquals(null, liveRetryStatusText(0))
        assertEquals(null, liveRetryStatusText(-1))
    }

    @Test fun liveConnectUsesConnectionWideStatusWording() {
        assertEquals("Connecting...", mainScreenOperationText(BleOperationKind.LIVE_CONNECT))
        assertEquals("Checking...", mainScreenOperationText(BleOperationKind.LIVE_REFRESH))
    }

    @Test fun verificationWarningOverridesStandbyAndGeneralStatuses() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = "Battery not verified after standby change",
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring"
        )

        assertEquals("Battery not verified after standby change", presentation.statusText)
        assertEquals(MainScreenStatusKind.VERIFICATION_WARNING, presentation.statusKind)
    }

    @Test fun standbyStatusOverridesGeneralStatus() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring"
        )

        assertEquals("Bluetooth connection timed out", presentation.statusText)
        assertEquals(MainScreenStatusKind.STANDBY_STATUS, presentation.statusKind)
    }

    @Test fun generalStatusIsUsedWhenNoHigherPriorityMessageExists() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = null,
            generalStatus = "Monitoring"
        )

        assertEquals("Monitoring", presentation.statusText)
        assertEquals(MainScreenStatusKind.GENERAL_STATUS, presentation.statusKind)
    }

    @Test fun blankAndWhitespaceOnlyInputsProduceBlankStatus() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = " ",
            standbyStatus = "",
            generalStatus = "   "
        )

        assertEquals("", presentation.statusText)
        assertEquals(MainScreenStatusKind.NONE, presentation.statusKind)
    }

    @Test fun resolvedStatusIsTrimmed() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = "  Standby on confirmed  ",
            generalStatus = " Monitoring "
        )

        assertEquals("Standby on confirmed", presentation.statusText)
    }

    @Test fun stoppedEligibleStatePresentsUncheckedEnabledStayConnected() {
        val presentation = stayConnectedPresentation(
            running = false,
            busy = false,
            bluetoothAvailable = true,
            footSelected = true,
            connectionState = LiveConnectionState.IDLE
        )

        assertFalse(presentation.checked)
        assertTrue(presentation.enabled)
    }

    @Test fun stayConnectedEnablementHasNoStandbyPrerequisite() {
        val byStandby = listOf(StandbyState.ON, StandbyState.OFF).associateWith { _ ->
            stayConnectedPresentation(
                running = false,
                busy = false,
                bluetoothAvailable = true,
                footSelected = true,
                connectionState = LiveConnectionState.IDLE
            )
        }

        assertTrue(byStandby.getValue(StandbyState.ON).enabled)
        assertTrue(byStandby.getValue(StandbyState.OFF).enabled)
        assertEquals(
            byStandby.getValue(StandbyState.ON),
            byStandby.getValue(StandbyState.OFF)
        )
    }

    @Test fun stoppedStateWithoutSelectedFootDisablesStayConnected() {
        val presentation = stayConnectedPresentation(
            running = false,
            busy = false,
            bluetoothAvailable = true,
            footSelected = false,
            connectionState = LiveConnectionState.IDLE
        )

        assertFalse(presentation.checked)
        assertFalse(presentation.enabled)
    }

    @Test fun stoppedStateWithoutBluetoothDisablesStayConnected() {
        val presentation = stayConnectedPresentation(
            running = false,
            busy = false,
            bluetoothAvailable = false,
            footSelected = true,
            connectionState = LiveConnectionState.IDLE
        )

        assertFalse(presentation.checked)
        assertFalse(presentation.enabled)
    }

    @Test fun stoppedBusyStateDisablesStayConnected() {
        val presentation = stayConnectedPresentation(
            running = false,
            busy = true,
            bluetoothAvailable = true,
            footSelected = true,
            connectionState = LiveConnectionState.IDLE
        )

        assertFalse(presentation.checked)
        assertFalse(presentation.enabled)
    }

    @Test fun runningReadyStatePresentsCheckedEnabledStayConnected() {
        val presentation = stayConnectedPresentation(
            running = true,
            busy = false,
            bluetoothAvailable = true,
            footSelected = true,
            connectionState = LiveConnectionState.READY
        )

        assertTrue(presentation.checked)
        assertTrue(presentation.enabled)
    }

    @Test fun runningBusyStateDisablesStayConnected() {
        val presentation = stayConnectedPresentation(
            running = true,
            busy = true,
            bluetoothAvailable = true,
            footSelected = true,
            connectionState = LiveConnectionState.READY
        )

        assertTrue(presentation.checked)
        assertFalse(presentation.enabled)
    }

    @Test fun runningFailedStateCanStillBeTurnedOff() {
        val presentation = stayConnectedPresentation(
            running = true,
            busy = false,
            bluetoothAvailable = true,
            footSelected = true,
            connectionState = LiveConnectionState.FAILED
        )

        assertTrue(presentation.checked)
        assertTrue(presentation.enabled)
    }

    @Test fun runningStateCanBeTurnedOffWithoutBluetooth() {
        val presentation = stayConnectedPresentation(
            running = true,
            busy = false,
            bluetoothAvailable = false,
            footSelected = true,
            connectionState = LiveConnectionState.READY
        )

        assertTrue(presentation.checked)
        assertTrue(presentation.enabled)
    }

    @Test fun disconnectingStatePresentsUncheckedDisabledStayConnected() {
        val presentation = stayConnectedPresentation(
            running = true,
            busy = false,
            bluetoothAvailable = true,
            footSelected = true,
            connectionState = LiveConnectionState.DISCONNECTING
        )

        assertFalse(presentation.checked)
        assertFalse(presentation.enabled)
    }
}
