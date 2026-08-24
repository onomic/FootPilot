package com.onomic.footpilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyOffAnkleRecoveryTest {
    @Test fun verifiedOffWithUnconfirmedAnkleQueriesExactlyOnce() = runBlocking {
        var queries = 0

        val result = recoverAnkleAfterVerifiedStandbyOff(
            read = verifiedRead(StandbyState.OFF),
            confirmedMd = null,
            queryAnkle = {
                queries++
                AnkleCommandExchangeResult.ResponseMissing("missing")
            }
        )

        assertEquals(1, queries)
        assertSame(StandbyOffAnkleRecoveryResult.Unconfirmed, result)
    }

    @Test fun supportedQueryResponseReturnsFreshConfirmation() = runBlocking {
        val result = recoverAnkleAfterVerifiedStandbyOff(
            read = verifiedRead(StandbyState.OFF),
            confirmedMd = null,
            queryAnkle = { ankleResponse(-500) }
        )

        assertEquals(StandbyOffAnkleRecoveryResult.Confirmed(-500), result)
    }

    @Test fun queryFailureLeavesSuccessfulStandbyNonRetryableAndAnkleUnconfirmed() = runBlocking {
        val read = verifiedRead(StandbyState.OFF)
        val priorAnkle = AnkleState(
            lastVerifiedMd = -500,
            certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND
        )

        val recovery = recoverAnkleAfterVerifiedStandbyOff(
            read = read,
            confirmedMd = priorAnkle.confirmedMd,
            queryAnkle = { AnkleCommandExchangeResult.WriteFailed("write failed") }
        )

        assertSame(StandbyOffAnkleRecoveryResult.Unconfirmed, recovery)
        assertTrue(read.verified)
        assertEquals(StandbyState.OFF, read.finalState)
        assertFalse(StandbyAttemptResult.Transaction(read).isRetryable())
        assertEquals(AnkleCertainty.UNKNOWN_AFTER_COMMAND, priorAnkle.certainty)
        assertEquals(-500, priorAnkle.lastVerifiedMd)
    }

    @Test fun unsupportedAngleDoesNotProduceConfirmation() = runBlocking {
        val result = recoverAnkleAfterVerifiedStandbyOff(
            read = verifiedRead(StandbyState.OFF),
            confirmedMd = null,
            queryAnkle = { ankleResponse(AnkleProtocol.MAX_MILLIDEGREES + 1) }
        )

        assertSame(StandbyOffAnkleRecoveryResult.Unconfirmed, result)
    }

    @Test fun recoveryFailureMessagePreservesHistoricalUnknownAfterCommandState() {
        val previous = AnkleRepo.state.value
        try {
            AnkleRepo.state.value = AnkleState(
                lastVerifiedMd = -500,
                lastVerifiedAt = 100L,
                certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND
            )

            AnkleRepo.fail("Check now to retry")

            assertEquals(AnkleCertainty.UNKNOWN_AFTER_COMMAND, AnkleRepo.state.value.certainty)
            assertEquals(-500, AnkleRepo.state.value.lastVerifiedMd)
            assertEquals(AnkleOperation.IDLE, AnkleRepo.state.value.operation)
            assertEquals("Check now to retry", AnkleRepo.state.value.message)
        } finally {
            AnkleRepo.state.value = previous
        }
    }

    @Test fun verifiedStandbyOnDoesNotQueryAnkle() = runBlocking {
        var queries = 0

        val result = recoverAnkleAfterVerifiedStandbyOff(
            read = verifiedRead(StandbyState.ON),
            confirmedMd = null,
            queryAnkle = {
                queries++
                ankleResponse(-500)
            }
        )

        assertSame(StandbyOffAnkleRecoveryResult.NotNeeded, result)
        assertEquals(0, queries)
    }

    @Test fun unverifiedStandbyOffDoesNotQueryAnkle() = runBlocking {
        var queries = 0
        val read = verifiedRead(StandbyState.OFF).copy(verified = false)

        val result = recoverAnkleAfterVerifiedStandbyOff(
            read = read,
            confirmedMd = null,
            queryAnkle = {
                queries++
                ankleResponse(-500)
            }
        )

        assertSame(StandbyOffAnkleRecoveryResult.NotNeeded, result)
        assertEquals(0, queries)
    }

    @Test fun alreadyConfirmedAnkleDoesNotQueryAgain() = runBlocking {
        var queries = 0

        val result = recoverAnkleAfterVerifiedStandbyOff(
            read = verifiedRead(StandbyState.OFF),
            confirmedMd = -500,
            queryAnkle = {
                queries++
                ankleResponse(-500)
            }
        )

        assertSame(StandbyOffAnkleRecoveryResult.NotNeeded, result)
        assertEquals(0, queries)
    }

    @Test fun cancellationFromRecoveryQueryIsPreserved() = runBlocking {
        var cancelled = false

        try {
            recoverAnkleAfterVerifiedStandbyOff(
                read = verifiedRead(StandbyState.OFF),
                confirmedMd = null,
                queryAnkle = { throw CancellationException("cancelled") }
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    private fun verifiedRead(state: StandbyState) = StandbyTransactionRead(
        requested = state,
        verified = true,
        finalState = state,
        batteryLevel = 80,
        ambiguous = false,
        error = null
    )

    private fun ankleResponse(millidegrees: Int) = AnkleCommandExchangeResult.Response(
        AnkleResponse(AnkleResponseKind.QUERY, millidegrees)
    )
}
