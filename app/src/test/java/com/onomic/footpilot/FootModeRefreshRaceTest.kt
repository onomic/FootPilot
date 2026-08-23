package com.onomic.footpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootModeRefreshRaceTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun beginRefreshRejectsCheckingWithoutMutatingEitherRow() {
        val store = store()
        assertTrue(store.beginRefresh(address))
        val checking = store.state.value

        assertFalse(store.beginRefresh(address))

        assertEquals(checking, store.state.value)
        FootMode.entries.forEach { mode ->
            assertEquals(FootModeOperation.CHECKING, store.state.value.status(mode).operation)
        }
    }

    @Test fun reservedDuplicateDoesNotCallBeginRefreshOrStartSecondSession() {
        val store = store()
        val slot = FootModeRefreshJobSlot<Any>()
        var beginCalls = 0
        var sessions = 0

        fun launch(): Boolean = slot.tryLaunch(
            beginRefresh = {
                beginCalls++
                store.beginRefresh(address)
            },
            create = { Any() },
            start = { sessions++ }
        )

        assertTrue(launch())
        assertFalse(launch())
        assertTrue(slot.isReserved())
        assertEquals(1, beginCalls)
        assertEquals(1, sessions)
    }

    @Test fun chairResultDuplicateThenRelaxResultLeavesBothConfirmedAndIdle() {
        val fixture = Fixture()
        assertTrue(fixture.launch())
        fixture.store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.ON))
        val chairBeforeDuplicate = fixture.store.state.value.chairExit

        assertFalse(fixture.launch())
        assertEquals(chairBeforeDuplicate, fixture.store.state.value.chairExit)
        assertEquals(FootModeOperation.IDLE, fixture.store.state.value.chairExit.operation)
        assertTrue(fixture.store.state.value.chairExit.currentConfirmed)

        fixture.store.applyQuery(address, query(FootMode.RELAX, FootModeValue.OFF))

        assertSettled(fixture.store)
        assertEquals(FootModeValue.ON, fixture.store.state.value.chairExit.lastVerified)
        assertEquals(FootModeValue.OFF, fixture.store.state.value.relax.lastVerified)
        assertEquals(1, fixture.sessions)
    }

    @Test fun relaxResultDuplicateThenChairResultPreservesTheSymmetricalPartialTruth() {
        val fixture = Fixture()
        assertTrue(fixture.launch())
        fixture.store.applyQuery(address, query(FootMode.RELAX, FootModeValue.ON))
        val relaxBeforeDuplicate = fixture.store.state.value.relax

        assertFalse(fixture.launch())
        assertEquals(relaxBeforeDuplicate, fixture.store.state.value.relax)
        assertEquals(FootModeOperation.IDLE, fixture.store.state.value.relax.operation)
        assertTrue(fixture.store.state.value.relax.currentConfirmed)

        fixture.store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.OFF))

        assertSettled(fixture.store)
        assertEquals(FootModeValue.OFF, fixture.store.state.value.chairExit.lastVerified)
        assertEquals(FootModeValue.ON, fixture.store.state.value.relax.lastVerified)
        assertEquals(1, fixture.sessions)
    }

    @Test fun laterSettingsEntryRefreshesNormallyOnlyAfterOldSlotClears() {
        val fixture = Fixture()
        assertTrue(fixture.launch())
        repeat(4) { assertFalse(fixture.launch()) }
        fixture.store.applyQuery(address, query(FootMode.CHAIR_EXIT, FootModeValue.ON))
        fixture.store.applyQuery(address, query(FootMode.RELAX, FootModeValue.OFF))
        assertSettled(fixture.store)

        assertTrue(fixture.slot.clearIf(requireNotNull(fixture.lastToken)))
        assertTrue(fixture.launch())

        assertEquals(2, fixture.sessions)
        FootMode.entries.forEach { mode ->
            assertEquals(FootModeOperation.CHECKING, fixture.store.state.value.status(mode).operation)
        }
    }

    private inner class Fixture {
        val store = store()
        val slot = FootModeRefreshJobSlot<Any>()
        var sessions = 0
        var lastToken: Any? = null

        fun launch(): Boolean = slot.tryLaunch(
            beginRefresh = { store.beginRefresh(address) },
            create = { Any().also { lastToken = it } },
            start = { sessions++ }
        )
    }

    private fun store() = FootModeStateStore(FootModesState(targetAddress = address))

    private fun query(mode: FootMode, value: FootModeValue) =
        FootModeQueryRead(mode, value, failure = null, error = null)

    private fun assertSettled(store: FootModeStateStore) {
        FootMode.entries.forEach { mode ->
            val status = store.state.value.status(mode)
            assertEquals(FootModeOperation.IDLE, status.operation)
            assertTrue(status.currentConfirmed)
        }
    }
}
