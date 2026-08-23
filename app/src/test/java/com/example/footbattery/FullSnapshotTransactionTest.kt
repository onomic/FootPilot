package com.example.footbattery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSnapshotTransactionTest {
    private val previous = SnapshotState(
        batteryLevel = 70,
        standby = StandbyState.OFF,
        lastChecked = 100L
    )

    @Test fun standbyOffQueriesAnkleBeforeReadingBattery() = runBlocking {
        val transport = FakeTransport(
            standbyRead = success(StandbyState.OFF),
            ankleRead = success(4499),
            batteryRead = success(85)
        )

        val read = FullSnapshotTransaction.execute(transport)

        assertEquals(listOf("standby", "ankle", "battery"), transport.calls)
        assertEquals(AnkleSnapshotDisposition.QUERIED, read.ankleDisposition)
        assertEquals(4499, read.ankleMd)
    }

    @Test fun standbyOnSkipsAnkleAndStillReadsBattery() = runBlocking {
        val transport = FakeTransport(standbyRead = success(StandbyState.ON))

        val read = FullSnapshotTransaction.execute(transport)

        assertEquals(listOf("standby", "battery"), transport.calls)
        assertEquals(AnkleSnapshotDisposition.SKIPPED_STANDBY_ON, read.ankleDisposition)
        assertNull(read.ankleMd)
        assertNull(read.ankleError)
        assertEquals(85, read.batteryLevel)
    }

    @Test fun standbyUnknownSkipsAnkleAndReadsBattery() = runBlocking {
        val transport = FakeTransport(standbyRead = success(StandbyState.UNKNOWN))

        val read = FullSnapshotTransaction.execute(transport)

        assertEquals(listOf("standby", "battery"), transport.calls)
        assertEquals(
            AnkleSnapshotDisposition.SKIPPED_STANDBY_UNKNOWN,
            read.ankleDisposition
        )
        assertNull(read.standby)
        assertTrue(read.standbyError.orEmpty().contains("unknown"))
    }

    @Test fun failedStandbySkipsAnkleAndAttemptsBattery() = runBlocking {
        val transport = FakeTransport(
            standbyRead = FullSnapshotFieldRead.Failed("Standby transport failed")
        )

        val read = FullSnapshotTransaction.execute(transport)

        assertEquals(listOf("standby", "battery"), transport.calls)
        assertEquals(
            AnkleSnapshotDisposition.SKIPPED_STANDBY_UNKNOWN,
            read.ankleDisposition
        )
        assertEquals("Standby transport failed", read.standbyError)
    }

    @Test fun batteryAndStandbyOnWithIntentionalSkipIsComplete() = runBlocking {
        val read = FullSnapshotTransaction.execute(
            FakeTransport(standbyRead = success(StandbyState.ON))
        )

        val decision = classifyFullSnapshotRead(previous, read, checkedAt = 200L)

        assertTrue(decision.reduction.completeSnapshotSaved)
        assertTrue(decision.result is FootOperationResult.Complete)
        assertFalse(decision.result is FootOperationResult.Partial)
        assertEquals(
            SnapshotState(85, StandbyState.ON, 200L),
            (decision.result as FootOperationResult.Complete).snapshot
        )
    }

    @Test fun attemptedAnkleFailureWhileStandbyOffRemainsPartial() = runBlocking {
        val read = FullSnapshotTransaction.execute(
            FakeTransport(
                standbyRead = success(StandbyState.OFF),
                ankleRead = FullSnapshotFieldRead.Failed("Ankle response timed out")
            )
        )

        val decision = classifyFullSnapshotRead(previous, read, checkedAt = 200L)

        assertEquals(AnkleSnapshotDisposition.QUERIED, read.ankleDisposition)
        assertTrue(decision.reduction.completeSnapshotSaved)
        assertEquals(
            FootOperationResult.Partial("Ankle response timed out"),
            decision.result
        )
    }

    @Test fun unavailableStandbyProducesStandbyPartialRatherThanAnkleFailure() = runBlocking {
        val read = FullSnapshotTransaction.execute(
            FakeTransport(
                standbyRead = FullSnapshotFieldRead.Failed("Standby response timed out")
            )
        )

        val decision = classifyFullSnapshotRead(previous, read, checkedAt = 200L)

        assertFalse(decision.reduction.completeSnapshotSaved)
        assertEquals(
            FootOperationResult.Partial("Standby response timed out"),
            decision.result
        )
    }

    @Test fun successfulFreshAnkleQueryBecomesCurrentConfirmedTruth() = runBlocking {
        val read = FullSnapshotTransaction.execute(
            FakeTransport(
                standbyRead = success(StandbyState.OFF),
                ankleRead = success(4499)
            )
        )

        val state = ankleStateAfterSnapshotRead(AnkleState(), read, verifiedAt = 200L)

        assertEquals(4499, state.confirmedMd)
        assertEquals(4499, state.lastVerifiedMd)
        assertEquals(200L, state.lastVerifiedAt)
        assertEquals(AnkleCertainty.CONFIRMED, state.certainty)
    }

    @Test fun intentionalSkipDemotesCachedConfirmationToHistoricalTruth() = runBlocking {
        val prior = AnkleState(
            lastVerifiedMd = 4499,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.CONFIRMED
        )
        val read = FullSnapshotTransaction.execute(
            FakeTransport(standbyRead = success(StandbyState.ON))
        )

        val state = ankleStateAfterSnapshotRead(prior, read, verifiedAt = 200L)
        val display = AnklePresentation.create(state, StandbyState.ON, controlsReady = true)

        assertEquals(AnkleCertainty.UNKNOWN, state.certainty)
        assertNull(state.confirmedMd)
        assertEquals(4499, state.lastVerifiedMd)
        assertEquals(100L, state.lastVerifiedAt)
        assertEquals("+4.5°", display.angleText)
        assertEquals("Last verified +4.5°", display.statusText)
        assertNull(display.historicalText)
        assertFalse(display.isCurrentConfirmed)
    }

    @Test fun intentionalSkipPreservesUnknownAfterCommandSafetyState() = runBlocking {
        val prior = AnkleState(
            lastVerifiedMd = 4499,
            lastVerifiedAt = 100L,
            certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND
        )
        val read = FullSnapshotTransaction.execute(
            FakeTransport(standbyRead = success(StandbyState.ON))
        )

        val state = ankleStateAfterSnapshotRead(prior, read, verifiedAt = 200L)

        assertEquals(AnkleCertainty.UNKNOWN_AFTER_COMMAND, state.certainty)
        assertNull(state.confirmedMd)
        assertEquals(4499, state.lastVerifiedMd)
    }

    private class FakeTransport(
        private val standbyRead: FullSnapshotFieldRead<StandbyState>,
        private val ankleRead: FullSnapshotFieldRead<Int> = success(4499),
        private val batteryRead: FullSnapshotFieldRead<Int> = success(85)
    ) : FullSnapshotTransport {
        val calls = mutableListOf<String>()

        override suspend fun queryStandby(): FullSnapshotFieldRead<StandbyState> {
            calls += "standby"
            return standbyRead
        }

        override suspend fun queryAnkle(): FullSnapshotFieldRead<Int> {
            calls += "ankle"
            return ankleRead
        }

        override suspend fun readBattery(): FullSnapshotFieldRead<Int> {
            calls += "battery"
            return batteryRead
        }
    }

    private companion object {
        fun <T> success(value: T): FullSnapshotFieldRead<T> =
            FullSnapshotFieldRead.Success(value)
    }
}
