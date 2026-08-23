package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkleStatePresentationTest {
    @Test fun firstInstallDecodesToUnknown() {
        val state = AnklePersistence.decode(StoredAnkleState(null, null, null))

        assertEquals(AnkleCertainty.UNKNOWN, state.certainty)
        assertNull(state.confirmedMd)
    }

    @Test fun exactConfirmedMillidegreesSurvivePersistence() {
        val state = AnkleState(
            lastVerifiedMd = 4499,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.CONFIRMED
        )

        assertEquals(state, AnklePersistence.decode(AnklePersistence.encode(state)))
    }

    @Test fun processRestoreTreatsPersistedConfirmationAsHistoricalUntilFreshQuery() {
        val restored = AnklePersistence.restoreForProcess(
            StoredAnkleState(4499, 100L, AnkleCertainty.CONFIRMED.name)
        )

        assertEquals(4499, restored.lastVerifiedMd)
        assertEquals(AnkleCertainty.UNKNOWN, restored.certainty)
        assertNull(restored.confirmedMd)
    }

    @Test fun confirmedAngleUsesCurrentPresentation() {
        val state = AnkleState(lastVerifiedMd = -400, certainty = AnkleCertainty.CONFIRMED)
        val display = AnklePresentation.create(state, StandbyState.OFF, controlsReady = true)

        assertEquals("-0.4°", display.angleText)
        assertEquals("Confirmed -0.4°", display.statusText)
        assertEquals("Confirmed ankle angle minus 0.4 degrees", display.angleContentDescription)
        assertTrue(display.isCurrentConfirmed)
        assertTrue(display.movementEnabled)
    }

    @Test fun ordinaryHistoricalAngleReplacesUnknownWithMutedValue() {
        val state = AnkleState(
            lastVerifiedMd = -400,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.UNKNOWN
        )
        val display = AnklePresentation.create(state, StandbyState.OFF, controlsReady = true)

        assertEquals("-0.4°", display.angleText)
        assertEquals("Last verified -0.4°", display.statusText)
        assertNull(display.historicalText)
        assertEquals(
            "Last verified ankle angle minus 0.4 degrees, current angle not confirmed",
            display.angleContentDescription
        )
        assertFalse(display.isCurrentConfirmed)
        assertHistoricalControlsDisabled(display)
        assertNull(state.confirmedMd)
    }

    @Test fun unknownAfterCommandShowsHistoricalValueWithSafetyDistinction() {
        val state = AnkleState(
            lastVerifiedMd = -400,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND
        )
        val display = AnklePresentation.create(state, StandbyState.OFF, controlsReady = true)

        assertEquals("-0.4°", display.angleText)
        assertEquals(
            "Last verified -0.4° · not verified after adjustment",
            display.statusText
        )
        assertNull(display.historicalText)
        assertFalse(display.isCurrentConfirmed)
        assertHistoricalControlsDisabled(display)
        assertNull(state.confirmedMd)
    }

    @Test fun ordinaryUnknownWithoutStoredAngleUsesLiteralUnknown() {
        val display = AnklePresentation.create(
            AnkleState(certainty = AnkleCertainty.UNKNOWN),
            StandbyState.OFF,
            controlsReady = true
        )

        assertEquals("Unknown", display.angleText)
        assertEquals("Ankle angle unknown", display.angleContentDescription)
        assertHistoricalControlsDisabled(display)
    }

    @Test fun unknownAfterCommandWithoutStoredAngleUsesLiteralUnknown() {
        val display = AnklePresentation.create(
            AnkleState(certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND),
            StandbyState.OFF,
            controlsReady = true
        )

        assertEquals("Unknown", display.angleText)
        assertEquals("Angle not verified after adjustment", display.statusText)
        assertEquals("Ankle angle unknown", display.angleContentDescription)
        assertHistoricalControlsDisabled(display)
    }

    @Test fun invalidStoredAngleIsNotPresentedAsKnown() {
        val display = AnklePresentation.create(
            AnkleState(lastVerifiedMd = 14_001, certainty = AnkleCertainty.UNKNOWN),
            StandbyState.OFF,
            controlsReady = true
        )

        assertEquals("Unknown", display.angleText)
    }

    @Test fun historicalAngleCannotEnableSavingOrActivePresetMatching() {
        val state = AnkleState(lastVerifiedMd = -400, certainty = AnkleCertainty.UNKNOWN)
        val targets = PresetTargets(runningMd = -400)

        assertNull(state.confirmedMd)
        assertTrue(targets.activeMatches(state.confirmedMd).isEmpty())
        assertFalse(state.confirmedMd != null)
    }

    @Test fun failedFreshQueryNeverLeavesPersistedValueCurrent() {
        val state = AnkleState(
            lastVerifiedMd = 4499,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.UNKNOWN
        )
        val display = AnklePresentation.create(state, StandbyState.OFF, controlsReady = true)

        assertNull(state.confirmedMd)
        assertEquals("+4.5°", display.angleText)
        assertEquals("Last verified +4.5°", display.statusText)
        assertFalse(display.isCurrentConfirmed)
        assertHistoricalControlsDisabled(display)
    }

    @Test fun unknownAfterCommandStateRemainsUnconfirmedDuringFailedRecovery() {
        val state = AnkleState(
            lastVerifiedMd = 4499,
            certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND,
            message = "Recovery query failed"
        )

        assertEquals(AnkleCertainty.UNKNOWN_AFTER_COMMAND, state.certainty)
        assertNull(state.confirmedMd)
    }

    @Test fun standbyOnKeepsHistoricalValueVisibleAndNonMovable() {
        val state = AnkleState(lastVerifiedMd = -400, certainty = AnkleCertainty.UNKNOWN)
        val display = AnklePresentation.create(state, StandbyState.ON, controlsReady = true)

        assertEquals("-0.4°", display.angleText)
        assertEquals("Last verified -0.4°", display.statusText)
        assertFalse(display.isCurrentConfirmed)
        assertHistoricalControlsDisabled(display)
    }

    @Test fun standbyOnLabelsKnownAngleLastVerifiedAndDisablesMovement() {
        val state = AnkleState(lastVerifiedMd = 4599, certainty = AnkleCertainty.CONFIRMED)
        val display = AnklePresentation.create(state, StandbyState.ON, controlsReady = true)

        assertEquals("+4.6°", display.angleText)
        assertEquals("Last verified +4.6°", display.statusText)
        assertFalse(display.isCurrentConfirmed)
        assertFalse(display.plusEnabled)
    }

    @Test fun fineButtonsUseNextExactTargetAtNonRoundBounds() {
        val low = AnklePresentation.create(
            AnkleState(lastVerifiedMd = -1950, certainty = AnkleCertainty.CONFIRMED),
            StandbyState.OFF,
            controlsReady = true
        )
        val high = AnklePresentation.create(
            AnkleState(lastVerifiedMd = 13950, certainty = AnkleCertainty.CONFIRMED),
            StandbyState.OFF,
            controlsReady = true
        )

        assertFalse(low.minusEnabled)
        assertTrue(low.plusEnabled)
        assertTrue(high.minusEnabled)
        assertFalse(high.plusEnabled)
    }

    private fun assertHistoricalControlsDisabled(display: AnkleValuePresentation) {
        assertFalse(display.movementEnabled)
        assertFalse(display.minusEnabled)
        assertFalse(display.plusEnabled)
    }
}
