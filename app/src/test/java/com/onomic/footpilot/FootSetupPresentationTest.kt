package com.onomic.footpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootSetupPresentationTest {
    private val selected = SelectedFoot("MyFoot", "AA:BB:CC:DD:EE:FF")

    @Test fun noSelectionIsCalmAndMuted() {
        val state = footSetupStatusPresentation(null, FootSetupFeedback.Idle)
        assertEquals("No foot selected", state.text)
        assertEquals(FootSetupStatusTone.MUTED, state.tone)
        assertFalse(state.showRemove)
    }

    @Test fun findingStateUsesEnteredName() {
        val state = footSetupStatusPresentation(selected, FootSetupFeedback.Finding("MyFoot"))
        assertEquals("Finding MyFoot…", state.text)
        assertFalse(state.showRemove)
    }

    @Test fun selectedStateShowsOnlyNameAndRemoveAction() {
        val state = footSetupStatusPresentation(selected, FootSetupFeedback.Idle)
        assertEquals("Selected: MyFoot", state.text)
        assertEquals(FootSetupStatusTone.SUCCESS, state.tone)
        assertTrue(state.showRemove)
        assertFalse(state.text.contains(selected.address))
    }

    @Test fun searchFailuresUseOneInlineWarningLine() {
        val notFound = footSetupStatusPresentation(
            selected,
            FootSetupFeedback.Error("Couldn't find OtherFoot. Check the name and try again.")
        )
        val incompatible = footSetupStatusPresentation(
            selected,
            FootSetupFeedback.Error("OtherFoot isn't a compatible foot.")
        )
        assertEquals(FootSetupStatusTone.WARNING, notFound.tone)
        assertEquals("OtherFoot isn't a compatible foot.", incompatible.text)
        assertFalse(notFound.showRemove)
    }

    @Test fun activeConnectionOrOperationBlocksTargetChanges() {
        val live = footSetupActionAvailability(true, false, false, false)
        val movement = footSetupActionAvailability(false, true, true, false)
        assertFalse(live.canChange)
        assertFalse(movement.canChange)
        assertEquals("Disconnect before changing the foot.", live.helperText)
    }

    @Test fun exactNameMatchingTrimsInputButNeverIgnoresCase() {
        assertTrue(exactAdvertisedNameMatch("  MyFoot  ", "MyFoot"))
        assertFalse(exactAdvertisedNameMatch("MyFoot", "myfoot"))
        assertFalse(exactAdvertisedNameMatch("MyFoot", "OtherFoot"))
    }
}
