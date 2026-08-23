package com.onomic.footpilot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkleTransactionTest {
    @Test fun freshStandbyOnBlocksMovementWithoutAnkleWrite() = runBlocking {
        val transport = FakeTransport(standby = standby(StandbyState.ON))

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(1000),
            transport
        )

        assertFalse(result.commandWriteAccepted)
        assertEquals(StandbyState.ON, result.freshStandby)
        assertTrue(result.error.orEmpty().contains("Turn standby off"))
        assertEquals(0, transport.ankleCommands.size)
    }

    @Test fun freshStandbyUnknownBlocksEvenWhenCallerCouldHaveCachedOff() = runBlocking {
        val transport = FakeTransport(
            standby = StandbyCommandExchangeResult.ResponseMissing("timed out")
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(1000),
            transport
        )

        assertFalse(result.commandWriteAccepted)
        assertNull(result.freshStandby)
        assertTrue(result.error.orEmpty().contains("Standby is unknown"))
        assertEquals(0, transport.ankleCommands.size)
    }

    @Test fun fineAdjustmentUsesFreshExactQueryValue() = runBlocking {
        val transport = FakeTransport(
            ankleSteps = listOf(
                response(AnkleResponseKind.QUERY, 4499),
                response(AnkleResponseKind.SET, 4599),
                response(AnkleResponseKind.QUERY, 4599)
            )
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Fine(FineAdjustment.PLUS),
            transport
        )

        assertEquals(4599, result.requestedMd)
        assertEquals(4599, result.finalConfirmedMd)
        assertTrue(result.requestSatisfied)
        assertTrue(result.finalTruthConfirmed)
        assertEquals(3, transport.ankleCommands.size)
        assertArrayEquals(AnkleProtocol.queryCommand(), transport.ankleCommands.first())
        assertArrayEquals(AnkleProtocol.queryCommand(), transport.ankleCommands.last())
        assertEquals(
            4599,
            AnkleProtocol.parseResponse(
                ankleResponseFromSetCommand(transport.ankleCommands[1])
            )?.millidegrees
        )
    }

    @Test fun nearBoundaryFineStepIsRejectedWithoutClampingOrSet() = runBlocking {
        val transport = FakeTransport(
            ankleSteps = listOf(response(AnkleResponseKind.QUERY, -1950))
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Fine(FineAdjustment.MINUS),
            transport
        )

        assertEquals(-1950, result.finalConfirmedMd)
        assertFalse(result.commandWriteAccepted)
        assertEquals(1, transport.ankleCommands.size)
    }

    @Test fun missingSetResponseStillUsesFinalQueryAsAuthority() = runBlocking {
        var potentialMovement = false
        val transport = FakeTransport(
            ankleSteps = listOf(
                response(AnkleResponseKind.QUERY, 4499),
                AnkleCommandExchangeResult.ResponseMissing("SET response timed out"),
                response(AnkleResponseKind.QUERY, 4599)
            )
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(4599),
            transport,
            onPotentialMovement = { potentialMovement = true }
        )

        assertTrue(potentialMovement)
        assertTrue(result.commandWriteAccepted)
        assertTrue(result.finalTruthConfirmed)
        assertTrue(result.requestSatisfied)
        assertFalse(result.unknownAfterCommand)
    }

    @Test fun setWriteRejectedBeforeAndroidAcceptanceKeepsInitialTruth() = runBlocking {
        var potentialMovement = false
        val transport = FakeTransport(
            ankleSteps = listOf(
                response(AnkleResponseKind.QUERY, 4499),
                AnkleCommandExchangeResult.WriteFailed("Android rejected the write")
            )
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(4599),
            transport,
            onPotentialMovement = { potentialMovement = true }
        )

        assertFalse(potentialMovement)
        assertFalse(result.commandWriteAccepted)
        assertFalse(result.unknownAfterCommand)
        assertTrue(result.finalTruthConfirmed)
        assertEquals(4499, result.finalConfirmedMd)
        assertEquals(2, transport.ankleCommands.size)
    }

    @Test fun acceptedWriteAndFailedFinalQueryBecomesUnknownAfterCommand() = runBlocking {
        var potentialMovement = false
        val transport = FakeTransport(
            ankleSteps = listOf(
                response(AnkleResponseKind.QUERY, 4499),
                AnkleCommandExchangeResult.ResponseMissing("SET response timed out"),
                AnkleCommandExchangeResult.ResponseMissing("QUERY response timed out")
            )
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(4599),
            transport,
            onPotentialMovement = { potentialMovement = true }
        )

        assertTrue(potentialMovement)
        assertTrue(result.commandWriteAccepted)
        assertTrue(result.unknownAfterCommand)
        assertFalse(result.finalTruthConfirmed)
        assertNull(result.finalConfirmedMd)
    }

    @Test fun callbackFailureAfterAcceptedWriteStillRequiresFinalFootTruth() = runBlocking {
        var potentialMovement = false
        val transport = FakeTransport(
            ankleSteps = listOf(
                response(AnkleResponseKind.QUERY, 4499),
                AnkleCommandExchangeResult.WriteFailed("GATT callback failed"),
                AnkleCommandExchangeResult.ResponseMissing("QUERY response timed out")
            ),
            acceptSetBeforeFailure = true
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(4599),
            transport,
            onPotentialMovement = { potentialMovement = true }
        )

        assertTrue(potentialMovement)
        assertTrue(result.commandWriteAccepted)
        assertTrue(result.unknownAfterCommand)
        assertFalse(result.finalTruthConfirmed)
        assertNull(result.finalConfirmedMd)
        assertEquals(3, transport.ankleCommands.size)
    }

    @Test fun oneMillidegreeDifferenceIsSuccessfulFootTruth() = runBlocking {
        val transport = FakeTransport(
            ankleSteps = listOf(
                response(AnkleResponseKind.QUERY, 4499),
                response(AnkleResponseKind.SET, 4500),
                response(AnkleResponseKind.QUERY, 4499)
            )
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(4500),
            transport
        )

        assertEquals(4499, result.finalConfirmedMd)
        assertTrue(result.finalTruthConfirmed)
        assertTrue(result.requestSatisfied)
        assertEquals("Ankle confirmed +4.5°", result.error)
    }

    @Test fun largerFinalMismatchIsConfirmedTruthButNotRequestSuccess() = runBlocking {
        val transport = FakeTransport(
            ankleSteps = listOf(
                response(AnkleResponseKind.QUERY, 4499),
                response(AnkleResponseKind.SET, 4501),
                response(AnkleResponseKind.QUERY, 4499)
            )
        )

        val result = AnkleTransaction.execute(
            AnkleTargetRequest.Absolute(4501),
            transport
        )

        assertEquals(4499, result.finalConfirmedMd)
        assertTrue(result.finalTruthConfirmed)
        assertFalse(result.requestSatisfied)
        assertTrue(result.error.orEmpty().contains("Foot confirmed +4.5°"))
    }

    private class FakeTransport(
        private val standby: StandbyCommandExchangeResult = standby(StandbyState.OFF),
        ankleSteps: List<AnkleCommandExchangeResult> = emptyList(),
        private val acceptSetBeforeFailure: Boolean = false
    ) : AnkleTransactionTransport {
        private val steps = ArrayDeque(ankleSteps)
        val ankleCommands = mutableListOf<ByteArray>()

        override suspend fun exchangeStandbyQuery(): StandbyCommandExchangeResult = standby

        override suspend fun exchangeAnkle(
            command: ByteArray,
            expectedKind: AnkleResponseKind,
            onWriteAccepted: () -> Unit
        ): AnkleCommandExchangeResult {
            ankleCommands += command
            return steps.removeFirst().also { result ->
                if (result !is AnkleCommandExchangeResult.WriteFailed ||
                    (acceptSetBeforeFailure && expectedKind == AnkleResponseKind.SET)
                ) {
                    onWriteAccepted()
                }
            }
        }
    }

    private companion object {
        fun standby(state: StandbyState) = StandbyCommandExchangeResult.Response(
            StandbyResponse(StandbyResponseKind.QUERY, state)
        )

        fun response(kind: AnkleResponseKind, md: Int) =
            AnkleCommandExchangeResult.Response(AnkleResponse(kind, md))

        fun ankleResponseFromSetCommand(command: ByteArray): ByteArray =
            byteArrayOf(
                0xB1.toByte(), 0xB0.toByte(), 0x08, 0x13, 0x34, 0xA0.toByte()
            ) + command.copyOfRange(6, 10)
    }
}
