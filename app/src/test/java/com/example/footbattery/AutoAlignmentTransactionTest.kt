package com.example.footbattery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAlignmentTransactionTest {
    @Test fun startCommandIsTheOnlyProvenOutgoingAutoPacket() {
        assertTrue(
            AutoAlignmentProtocol.startCommand().contentEquals(
                byteArrayOf(0xB2.toByte(), 0xB0.toByte(), 0x04, 0x00)
            )
        )
    }

    @Test fun angleBeforeCompletionIsIntermediateAndFinalQueryWins() = runBlocking {
        val transport = FakeAutoTransport(
            querySteps = listOf(ankle(4599), ankle(569)),
            waitSteps = listOf(
                AutoEventWaitResult.Event(Aa01Event.AutoActivity(0x00, emptyList())),
                AutoEventWaitResult.Event(Aa01Event.Ankle(AnkleResponse(AnkleResponseKind.QUERY, 277))),
                AutoEventWaitResult.Event(Aa01Event.AutoCompletion(emptyList()))
            )
        )
        val operations = mutableListOf<AnkleOperation>()
        var potentialMovement = false

        val result = AutoAlignmentTransaction.execute(
            transport = transport,
            onOperation = { operations += it },
            onPotentialMovement = { potentialMovement = true }
        )

        assertTrue(potentialMovement)
        assertTrue(result.startWriteAccepted)
        assertTrue(result.completionObserved)
        assertTrue(result.finalTruthConfirmed)
        assertEquals(569, result.finalConfirmedMd)
        assertEquals(
            listOf(
                AnkleOperation.AUTO_STARTING,
                AnkleOperation.AUTO_RUNNING,
                AnkleOperation.VERIFYING
            ),
            operations
        )
    }

    @Test fun missingAcknowledgementRetainsRecoveryOwnershipForLaterCompletion() = runBlocking {
        val transport = FakeAutoTransport(
            querySteps = listOf(ankle(8000), ankle(277)),
            waitSteps = listOf(
                AutoEventWaitResult.TimedOut,
                AutoEventWaitResult.Event(Aa01Event.AutoActivity(0x1E, emptyList())),
                AutoEventWaitResult.Event(Aa01Event.AutoCompletion(emptyList()))
            )
        )

        val result = AutoAlignmentTransaction.execute(transport)

        assertTrue(result.completionObserved)
        assertEquals(277, result.finalConfirmedMd)
        assertEquals(
            listOf(
                AutoAlignmentTransaction.START_ACK_TIMEOUT_MS,
                AutoAlignmentTransaction.INACTIVITY_TIMEOUT_MS,
                AutoAlignmentTransaction.INACTIVITY_TIMEOUT_MS
            ),
            transport.waitTimeouts
        )
    }

    @Test fun modeEventIsIgnoredWhileAutoWaitsForCompletion() = runBlocking {
        val transport = FakeAutoTransport(
            querySteps = listOf(ankle(8000), ankle(569)),
            waitSteps = listOf(
                AutoEventWaitResult.Event(
                    Aa01Event.FootMode(
                        FootModeResponse(
                            FootMode.RELAX,
                            FootModeResponseKind.QUERY,
                            FootModeValue.ON
                        ),
                        trailingBytes = listOf(0x55)
                    )
                ),
                AutoEventWaitResult.Event(Aa01Event.AutoCompletion(emptyList()))
            )
        )

        val result = AutoAlignmentTransaction.execute(transport)

        assertTrue(result.completionObserved)
        assertEquals(569, result.finalConfirmedMd)
        assertEquals(2, transport.waitTimeouts.size)
    }

    @Test fun inactivityTimeoutWithSuccessfulFinalQueryRestoresTruthWithoutSuccess() = runBlocking {
        val transport = FakeAutoTransport(
            querySteps = listOf(ankle(8000), ankle(569)),
            waitSteps = listOf(AutoEventWaitResult.TimedOut, AutoEventWaitResult.TimedOut)
        )

        val result = AutoAlignmentTransaction.execute(transport)

        assertFalse(result.completionObserved)
        assertTrue(result.finalTruthConfirmed)
        assertFalse(result.unknownAfterCommand)
        assertEquals(569, result.finalConfirmedMd)
        assertTrue(result.error.orEmpty().contains("completion was not confirmed"))
    }

    @Test fun timeoutAndFailedFinalQueryBecomeUnknownAfterCommand() = runBlocking {
        val transport = FakeAutoTransport(
            querySteps = listOf(
                ankle(8000),
                AnkleCommandExchangeResult.ResponseMissing("final timeout")
            ),
            waitSteps = listOf(AutoEventWaitResult.TimedOut, AutoEventWaitResult.TimedOut)
        )

        val result = AutoAlignmentTransaction.execute(transport)

        assertTrue(result.startWriteAccepted)
        assertTrue(result.unknownAfterCommand)
        assertNull(result.finalConfirmedMd)
    }

    @Test fun disconnectDuringRunStillAttemptsVerificationThenBecomesUnknown() = runBlocking {
        val transport = FakeAutoTransport(
            querySteps = listOf(
                ankle(8000),
                AnkleCommandExchangeResult.WriteFailed("Foot disconnected")
            ),
            waitSteps = listOf(AutoEventWaitResult.Failed("Foot disconnected"))
        )

        val result = AutoAlignmentTransaction.execute(transport)

        assertTrue(result.unknownAfterCommand)
        assertFalse(result.finalTruthConfirmed)
    }

    @Test fun callbackFailureAfterAcceptedStartStillUsesUnknownRecovery() = runBlocking {
        var potentialMovement = false
        val transport = FakeAutoTransport(
            querySteps = listOf(
                ankle(8000),
                AnkleCommandExchangeResult.ResponseMissing("final timeout")
            ),
            waitSteps = listOf(AutoEventWaitResult.Failed("GATT callback failed")),
            startResult = AutoStartWriteResult.Failed("GATT callback failed"),
            acceptStartBeforeFailure = true
        )

        val result = AutoAlignmentTransaction.execute(
            transport,
            onPotentialMovement = { potentialMovement = true }
        )

        assertTrue(potentialMovement)
        assertTrue(result.startWriteAccepted)
        assertTrue(result.unknownAfterCommand)
        assertFalse(result.finalTruthConfirmed)
        assertNull(result.finalConfirmedMd)
    }

    @Test fun startRejectedBeforeAndroidAcceptanceKeepsInitialTruth() = runBlocking {
        var potentialMovement = false
        val transport = FakeAutoTransport(
            querySteps = listOf(ankle(8000)),
            waitSteps = emptyList(),
            startResult = AutoStartWriteResult.Failed("Android rejected the write")
        )

        val result = AutoAlignmentTransaction.execute(
            transport,
            onPotentialMovement = { potentialMovement = true }
        )

        assertFalse(potentialMovement)
        assertFalse(result.startWriteAccepted)
        assertFalse(result.unknownAfterCommand)
        assertTrue(result.finalTruthConfirmed)
        assertEquals(8000, result.finalConfirmedMd)
        assertEquals(1, transport.startWrites)
    }

    @Test fun standbyOnBlocksAutoBeforeStartWrite() = runBlocking {
        val transport = FakeAutoTransport(
            standbyResult = standby(StandbyState.ON),
            querySteps = emptyList(),
            waitSteps = emptyList()
        )

        val result = AutoAlignmentTransaction.execute(transport)

        assertFalse(result.startWriteAccepted)
        assertEquals(0, transport.startWrites)
    }

    private class FakeAutoTransport(
        private val standbyResult: StandbyCommandExchangeResult = standby(StandbyState.OFF),
        querySteps: List<AnkleCommandExchangeResult>,
        waitSteps: List<AutoEventWaitResult>,
        private val startResult: AutoStartWriteResult = AutoStartWriteResult.Accepted,
        private val acceptStartBeforeFailure: Boolean = false
    ) : AutoAlignmentTransport {
        private val queries = ArrayDeque(querySteps)
        private val waits = ArrayDeque(waitSteps)
        val waitTimeouts = mutableListOf<Long>()
        var startWrites = 0

        override suspend fun exchangeStandbyQuery(): StandbyCommandExchangeResult = standbyResult

        override suspend fun exchangeAnkleQuery(): AnkleCommandExchangeResult = queries.removeFirst()

        override suspend fun writeStart(onWriteAccepted: () -> Unit): AutoStartWriteResult {
            startWrites++
            if (startResult is AutoStartWriteResult.Accepted || acceptStartBeforeFailure) {
                onWriteAccepted()
            }
            return startResult
        }

        override suspend fun awaitRelevantEvent(timeoutMs: Long): AutoEventWaitResult {
            waitTimeouts += timeoutMs
            return waits.removeFirst()
        }
    }

    private companion object {
        fun standby(state: StandbyState) = StandbyCommandExchangeResult.Response(
            StandbyResponse(StandbyResponseKind.QUERY, state)
        )

        fun ankle(md: Int) = AnkleCommandExchangeResult.Response(
            AnkleResponse(AnkleResponseKind.QUERY, md)
        )
    }
}
