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

    @Test fun heightClassesExposeTheExpectedGaugeAndCardSizes() {
        val compact = mainScreenLayoutSpec(640f, 1f)
        val regular = mainScreenLayoutSpec(760f, 1f)
        val tall = mainScreenLayoutSpec(900f, 1f)

        assertEquals(196f, compact.gaugeSizeDp, 0f)
        assertEquals(112f, compact.cardMinHeightDp, 0f)
        assertEquals(212f, regular.gaugeSizeDp, 0f)
        assertEquals(118f, regular.cardMinHeightDp, 0f)
        assertEquals(220f, tall.gaugeSizeDp, 0f)
        assertEquals(120f, tall.cardMinHeightDp, 0f)
    }

    @Test fun activeOperationOverridesVerificationAndOtherStatuses() {
        val presentation = MainScreenPresentation.create(
            activeOperationText = "Turning standby on...",
            verificationMessage = "Battery not verified after standby change",
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring"
        )

        assertEquals("Turning standby on...", presentation.statusText)
        assertEquals(MainScreenStatusKind.ACTIVE_OPERATION, presentation.statusKind)
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

    @Test fun stoppedStatePresentsEnabledStartAction() {
        val action = mainScreenContextualAction(
            running = false,
            busy = false,
            bluetoothAvailable = true
        )

        assertEquals(MainScreenContextualActionType.START, action.type)
        assertEquals("Start", action.label)
        assertTrue(action.enabled)
    }

    @Test fun runningStatePresentsEnabledDisconnectAction() {
        val action = mainScreenContextualAction(
            running = true,
            busy = false,
            bluetoothAvailable = true
        )

        assertEquals(MainScreenContextualActionType.DISCONNECT, action.type)
        assertEquals("Disconnect", action.label)
        assertTrue(action.enabled)
    }

    @Test fun contextualActionPreservesExistingEnablementRules() {
        assertFalse(mainScreenContextualAction(false, busy = true, bluetoothAvailable = true).enabled)
        assertFalse(mainScreenContextualAction(false, busy = false, bluetoothAvailable = false).enabled)
        assertFalse(mainScreenContextualAction(true, busy = true, bluetoothAvailable = true).enabled)
        assertTrue(mainScreenContextualAction(true, busy = false, bluetoothAvailable = false).enabled)
    }
}
