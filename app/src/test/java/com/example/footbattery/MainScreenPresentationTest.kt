package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenPresentationTest {
    @Test fun activeOperationOverridesVerificationAndStandbyStatus() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = "Turning standby on...",
            verificationMessage = "Battery not verified after standby change",
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring"
        )

        assertEquals("Turning standby on...", messages.standbyCardMessage)
    }

    @Test fun verificationWarningOverridesOrdinaryStandbyStatus() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = null,
            verificationMessage = "Battery not verified after standby change",
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring"
        )

        assertEquals(
            "Battery not verified after standby change",
            messages.standbyCardMessage
        )
    }

    @Test fun standbyStatusIsUsedWithoutOperationOrVerificationWarning() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring"
        )

        assertEquals("Bluetooth connection timed out", messages.standbyCardMessage)
    }

    @Test fun emptyInputsProduceBlankReservedMessages() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = null,
            verificationMessage = " ",
            standbyStatus = "",
            generalStatus = null
        )

        assertEquals("", messages.standbyCardMessage)
        assertEquals("", messages.footerStatus)
    }

    @Test fun identicalCardAndFooterMessagesAreDeduplicated() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Bluetooth connection timed out"
        )

        assertEquals("Bluetooth connection timed out", messages.standbyCardMessage)
        assertEquals("", messages.footerStatus)
    }

    @Test fun distinctGeneralStatusRemainsVisible() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = "Bluetooth connection timed out",
            generalStatus = "Monitoring"
        )

        assertEquals("Monitoring", messages.footerStatus)
    }

    @Test fun blankGeneralStatusRemainsBlank() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = "Check now to verify standby",
            generalStatus = "   "
        )

        assertEquals("", messages.footerStatus)
    }

    @Test fun trimmingPreventsWhitespaceVariantsFromRepeating() {
        val messages = MainScreenMessagePresentation.create(
            activeOperationText = null,
            verificationMessage = null,
            standbyStatus = "  Bluetooth connection timed out  ",
            generalStatus = " Bluetooth connection timed out "
        )

        assertEquals("Bluetooth connection timed out", messages.standbyCardMessage)
        assertEquals("", messages.footerStatus)
    }
}
