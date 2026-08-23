package com.onomic.footpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetRepositoryTest {
    @Test fun fixedOrderAndFirstInstallAreImmutableAndUnconfigured() {
        assertEquals(
            listOf(
                FootwearPreset.BAREFOOT,
                FootwearPreset.RUNNING,
                FootwearPreset.DRESS,
                FootwearPreset.BOOTS
            ),
            FootwearPreset.fixedOrder
        )
        val targets = PresetTargets()
        FootwearPreset.fixedOrder.forEach { assertNull(targets.target(it)) }
    }

    @Test fun savingChangesOnlySelectedSlotAndRetainsExactInt() {
        val original = PresetTargets(barefootMd = 569, dressMd = 7261)
        val saved = original.save(FootwearPreset.RUNNING, 4599)

        assertEquals(569, saved.target(FootwearPreset.BAREFOOT))
        assertEquals(4599, saved.target(FootwearPreset.RUNNING))
        assertEquals(7261, saved.target(FootwearPreset.DRESS))
        assertNull(saved.target(FootwearPreset.BOOTS))
    }

    @Test fun activeMatchUsesExactCanonicalEquality() {
        val targets = PresetTargets(runningMd = 4599, dressMd = 4599, bootsMd = 4600)

        assertEquals(
            listOf(FootwearPreset.RUNNING, FootwearPreset.DRESS),
            targets.activeMatches(4599)
        )
        assertEquals(emptyList<FootwearPreset>(), targets.activeMatches(4598))
    }

    @Test fun selectedMatchingSlotWinsSummaryOtherwiseFixedOrderWins() {
        val targets = PresetTargets(barefootMd = 569, runningMd = 569)

        assertEquals(
            FootwearPreset.RUNNING,
            summaryPreset(PresetState(targets, FootwearPreset.RUNNING), 569)
        )
        assertEquals(
            FootwearPreset.BAREFOOT,
            summaryPreset(PresetState(targets, FootwearPreset.DRESS), 569)
        )
    }

    @Test fun automaticAlignmentCannotMutatePresetValueModel() {
        val before = PresetTargets(runningMd = 4599)
        val autoConfirmedMd = 569

        assertEquals(4599, before.target(FootwearPreset.RUNNING))
        assertEquals(emptyList<FootwearPreset>(), before.activeMatches(autoConfirmedMd))
    }
}
