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

    @Test fun unknownAfterCommandRetainsOnlyExplicitHistoricalValue() {
        val state = AnkleState(
            lastVerifiedMd = 4499,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND
        )
        val display = AnklePresentation.create(state, StandbyState.OFF, controlsReady = true)

        assertEquals("Unknown", display.angleText)
        assertEquals("Last verified +4.5°", display.historicalText)
        assertFalse(display.isCurrentConfirmed)
        assertFalse(display.movementEnabled)
        assertNull(state.confirmedMd)
    }

    @Test fun failedFreshQueryNeverLeavesPersistedValueCurrent() {
        val state = AnkleState(
            lastVerifiedMd = 4499,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.UNKNOWN
        )
        val display = AnklePresentation.create(state, StandbyState.OFF, controlsReady = true)

        assertNull(state.confirmedMd)
        assertEquals("Unknown", display.angleText)
        assertEquals("Last verified +4.5°", display.historicalText)
        assertFalse(display.isCurrentConfirmed)
        assertFalse(display.movementEnabled)
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
}
