package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootModeStateTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun noFootSelectedMakesBothRowsUnavailable() {
        FootMode.entries.forEach { mode ->
            val display = FootModePresentation.create(
                mode,
                FootModeStatus(),
                hasSelectedFoot = false,
                controlsAvailable = true
            )
            assertFalse(display.enabled)
            assertEquals("No foot selected", display.secondaryText)
            assertEquals("Not checked", display.stateDescription)
        }
    }

    @Test fun neverQueriedIsDisabledAndNotChecked() {
        val display = FootModePresentation.create(
            FootMode.CHAIR_EXIT,
            FootModeStatus(),
            hasSelectedFoot = true,
            controlsAvailable = true
        )

        assertFalse(display.checked)
        assertFalse(display.enabled)
        assertEquals("Not checked", display.secondaryText)
        assertEquals("Chair Exit Mode, Not checked", display.contentDescription)
    }

    @Test fun refreshDisablesBothRowsWithCheckingText() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        assertTrue(store.beginRefresh(address))

        FootMode.entries.forEach { mode ->
            val display = presentation(store, mode)
            assertFalse(display.enabled)
            assertEquals("Checking...", display.secondaryText)
            assertEquals("Checking", display.stateDescription)
        }
    }

    @Test fun confirmedOnIsCheckedEnabledAndAccessible() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.ON))

        val display = presentation(store, FootMode.CHAIR_EXIT)

        assertTrue(display.checked)
        assertTrue(display.enabled)
        assertNull(display.secondaryText)
        assertEquals("Chair Exit Mode, On", display.contentDescription)
    }

    @Test fun confirmedOffIsUncheckedEnabledAndAccessible() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        store.applyQuery(address, query(FootMode.RELAX, FootModeValue.OFF))

        val display = presentation(store, FootMode.RELAX)

        assertFalse(display.checked)
        assertTrue(display.enabled)
        assertNull(display.secondaryText)
        assertEquals("Relax Mode, Off", display.contentDescription)
    }

    @Test fun ambiguousCommandRetainsHistoryButDisablesAndSaysNotConfirmed() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.OFF))
        val token = store.beginIntent(address, FootMode.CHAIR_EXIT, FootModeValue.ON)
        store.applyMutation(token, failedRead(FootMode.CHAIR_EXIT, ambiguous = true))

        val display = presentation(store, FootMode.CHAIR_EXIT)

        assertFalse(display.checked)
        assertFalse(display.enabled)
        assertEquals("Not confirmed", display.secondaryText)
        assertEquals("Chair Exit Mode, Not confirmed", display.contentDescription)
    }

    @Test fun retryCountdownIsInlineAndDisabled() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        val token = store.beginIntent(address, FootMode.RELAX, FootModeValue.ON)
        store.applyMutation(token, failedRead(FootMode.RELAX, ambiguous = true))
        store.beginRetry(token)
        store.updateRetrySeconds(token, 8)

        val display = presentation(store, FootMode.RELAX)

        assertFalse(display.enabled)
        assertEquals("Retrying in 8s...", display.secondaryText)
        assertEquals("Not confirmed", display.stateDescription)
    }

    @Test fun settingStateIsDisabledAndNeverAnnouncedAsConfirmed() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.OFF))
        store.beginIntent(address, FootMode.CHAIR_EXIT, FootModeValue.ON)

        val display = presentation(store, FootMode.CHAIR_EXIT)

        assertFalse(display.enabled)
        assertEquals("Turning on...", display.secondaryText)
        assertEquals("Chair Exit Mode, Not confirmed", display.contentDescription)
    }

    @Test fun verifiedRetrySuccessClearsSecondaryTextAndEnablesTarget() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        val token = store.beginIntent(address, FootMode.RELAX, FootModeValue.ON)
        store.applyMutation(token, failedRead(FootMode.RELAX, ambiguous = true))
        store.beginRetry(token)
        store.beginRetryAttempt(token)
        store.applyMutation(token, verifiedRead(FootMode.RELAX, FootModeValue.ON))

        val display = presentation(store, FootMode.RELAX)

        assertTrue(display.checked)
        assertTrue(display.enabled)
        assertNull(display.secondaryText)
    }

    @Test fun selectedFootChangeClearsBothModeTruth() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.ON))
        store.applyQuery(address, query(FootMode.RELAX, FootModeValue.ON))

        store.resetTarget("11:22:33:44:55:66")

        assertNull(store.state.value.chairExit.lastVerified)
        assertNull(store.state.value.relax.lastVerified)
        assertFalse(store.state.value.chairExit.currentConfirmed)
        assertFalse(store.state.value.relax.currentConfirmed)
    }

    @Test fun chairAndRelaxUpdatesRemainIndependent() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        store.applyQuery(address, query(FootMode.RELAX, FootModeValue.OFF))
        val relaxBefore = store.state.value.relax

        store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.ON))

        assertEquals(relaxBefore, store.state.value.relax)
        assertEquals(FootModeValue.ON, store.state.value.chairExit.lastVerified)
    }

    @Test fun relaxUpdateDoesNotAlterChairState() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.OFF))
        val chairBefore = store.state.value.chairExit

        store.applyQuery(address, query(FootMode.RELAX, FootModeValue.ON))

        assertEquals(chairBefore, store.state.value.chairExit)
        assertEquals(FootModeValue.ON, store.state.value.relax.lastVerified)
    }

    @Test fun newerOppositeIntentInvalidatesOlderGeneration() {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        val on = store.beginIntent(address, FootMode.CHAIR_EXIT, FootModeValue.ON)
        val off = store.beginIntent(address, FootMode.CHAIR_EXIT, FootModeValue.OFF)

        assertFalse(store.isCurrent(on))
        assertTrue(store.isCurrent(off))
        assertEquals(FootModeValue.OFF, store.state.value.chairExit.requested)
    }

    private fun presentation(store: FootModeStateStore, mode: FootMode) =
        FootModePresentation.create(
            mode,
            store.state.value.status(mode),
            hasSelectedFoot = true,
            controlsAvailable = true
        )

    private fun query(mode: FootMode, value: FootModeValue) =
        FootModeQueryRead(mode, value, null, null)

    private fun failedRead(mode: FootMode, ambiguous: Boolean) = FootModeTransactionRead(
        mode = mode,
        requested = FootModeValue.ON,
        verified = false,
        finalValue = null,
        ambiguous = ambiguous,
        setWriteAccepted = ambiguous,
        failure = FootModeTransactionFailure.FINAL_QUERY_RESPONSE_MISSING,
        error = "${mode.displayName} not confirmed"
    )

    private fun verifiedRead(mode: FootMode, value: FootModeValue) = FootModeTransactionRead(
        mode = mode,
        requested = value,
        verified = true,
        finalValue = value,
        ambiguous = false,
        setWriteAccepted = false,
        failure = null,
        error = null
    )
}
