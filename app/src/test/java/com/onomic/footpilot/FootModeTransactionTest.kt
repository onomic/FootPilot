package com.onomic.footpilot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FootModeTransactionTest {
    @Test fun freshQueryAlreadyOnVerifiesBothModesWithoutSet() = runBlocking {
        FootMode.entries.forEach { mode ->
            val transport = FakeTransport(listOf(query(mode, FootModeValue.ON)))
            val result = FootModeTransaction.execute(mode, FootModeValue.ON, transport)

            assertTrue(result.verified)
            assertEquals(FootModeValue.ON, result.finalValue)
            assertEquals(1, transport.commands.size)
            assertArrayEquals(FootModeProtocol.queryCommand(mode), transport.commands.single())
        }
    }

    @Test fun freshQueryAlreadyOffVerifiesBothModesWithoutSet() = runBlocking {
        FootMode.entries.forEach { mode ->
            val transport = FakeTransport(listOf(query(mode, FootModeValue.OFF)))
            val result = FootModeTransaction.execute(mode, FootModeValue.OFF, transport)

            assertTrue(result.verified)
            assertEquals(FootModeValue.OFF, result.finalValue)
            assertEquals(1, transport.commands.size)
        }
    }

    @Test fun differingInitialValueUsesExactAbsoluteSetAndFinalQueryForBothModes() = runBlocking {
        FootMode.entries.forEach { mode ->
            val transport = FakeTransport(
                listOf(
                    query(mode, FootModeValue.OFF),
                    set(mode, FootModeValue.ON),
                    query(mode, FootModeValue.ON)
                )
            )
            val result = FootModeTransaction.execute(mode, FootModeValue.ON, transport)

            assertTrue(result.verified)
            assertTrue(result.setWriteAccepted)
            assertArrayEquals(FootModeProtocol.queryCommand(mode), transport.commands[0])
            assertArrayEquals(
                FootModeProtocol.setCommand(mode, FootModeValue.ON),
                transport.commands[1]
            )
            assertArrayEquals(FootModeProtocol.queryCommand(mode), transport.commands[2])
        }
    }

    @Test fun missingSetResponseStillPerformsAuthoritativeFinalQuery() = runBlocking {
        val mode = FootMode.RELAX
        val transport = FakeTransport(
            listOf(
                query(mode, FootModeValue.OFF),
                FootModeCommandExchangeResult.ResponseMissing("SET response timed out"),
                query(mode, FootModeValue.ON)
            )
        )

        val result = FootModeTransaction.execute(mode, FootModeValue.ON, transport)

        assertTrue(result.verified)
        assertTrue(result.setWriteAccepted)
        assertEquals(3, transport.commands.size)
    }

    @Test fun finalQueryOppositeTargetIsKnownFailureNotSuccess() = runBlocking {
        val mode = FootMode.CHAIR_EXIT
        val transport = FakeTransport(
            listOf(
                query(mode, FootModeValue.OFF),
                set(mode, FootModeValue.ON),
                query(mode, FootModeValue.OFF)
            )
        )

        val result = FootModeTransaction.execute(mode, FootModeValue.ON, transport)

        assertFalse(result.verified)
        assertFalse(result.ambiguous)
        assertEquals(FootModeValue.OFF, result.finalValue)
        assertEquals(FootModeTransactionFailure.FINAL_VALUE_MISMATCH, result.failure)
    }

    @Test fun setWriteFailureDoesNotClaimAmbiguity() = runBlocking {
        val mode = FootMode.RELAX
        val transport = FakeTransport(
            listOf(
                query(mode, FootModeValue.OFF),
                FootModeCommandExchangeResult.WriteFailed("Android rejected the write")
            )
        )

        val result = FootModeTransaction.execute(mode, FootModeValue.ON, transport)

        assertFalse(result.verified)
        assertFalse(result.ambiguous)
        assertFalse(result.setWriteAccepted)
        assertEquals(FootModeValue.OFF, result.finalValue)
        assertEquals(2, transport.commands.size)
    }

    @Test fun finalQueryFailureAfterAcceptedSetIsAmbiguous() = runBlocking {
        val mode = FootMode.CHAIR_EXIT
        val transport = FakeTransport(
            listOf(
                query(mode, FootModeValue.OFF),
                FootModeCommandExchangeResult.ResponseMissing("SET response timed out"),
                FootModeCommandExchangeResult.ResponseMissing("QUERY response timed out")
            )
        )

        val result = FootModeTransaction.execute(mode, FootModeValue.ON, transport)

        assertFalse(result.verified)
        assertTrue(result.ambiguous)
        assertTrue(result.setWriteAccepted)
        assertNull(result.finalValue)
    }

    @Test fun relaxResponseCannotSatisfyChairInitialQuery() = runBlocking {
        val transport = FakeTransport(listOf(query(FootMode.RELAX, FootModeValue.ON)))

        val result = FootModeTransaction.execute(
            FootMode.CHAIR_EXIT,
            FootModeValue.ON,
            transport
        )

        assertFalse(result.verified)
        assertEquals(FootModeTransactionFailure.INITIAL_QUERY_RESPONSE_INVALID, result.failure)
        assertEquals(1, transport.commands.size)
    }

    @Test fun chairResponseCannotSatisfyRelaxFinalQuery() = runBlocking {
        val transport = FakeTransport(
            listOf(
                query(FootMode.RELAX, FootModeValue.OFF),
                set(FootMode.RELAX, FootModeValue.ON),
                query(FootMode.CHAIR_EXIT, FootModeValue.ON)
            )
        )

        val result = FootModeTransaction.execute(
            FootMode.RELAX,
            FootModeValue.ON,
            transport
        )

        assertFalse(result.verified)
        assertTrue(result.ambiguous)
        assertEquals(FootModeTransactionFailure.FINAL_QUERY_RESPONSE_INVALID, result.failure)
    }

    @Test fun unknownCannotBeRequested() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                FootModeTransaction.execute(
                    FootMode.RELAX,
                    FootModeValue.UNKNOWN,
                    FakeTransport(emptyList())
                )
            }
        }
    }

    private class FakeTransport(
        results: List<FootModeCommandExchangeResult>
    ) : FootModeTransactionTransport {
        private val remaining = ArrayDeque(results)
        val commands = mutableListOf<ByteArray>()
        val expectedModes = mutableListOf<FootMode>()
        val expectedKinds = mutableListOf<FootModeResponseKind>()
        val expectedValues = mutableListOf<FootModeValue?>()

        override suspend fun exchange(
            mode: FootMode,
            command: ByteArray,
            expectedKind: FootModeResponseKind,
            expectedValue: FootModeValue?
        ): FootModeCommandExchangeResult {
            expectedModes += mode
            commands += command.copyOf()
            expectedKinds += expectedKind
            expectedValues += expectedValue
            return remaining.removeFirst()
        }
    }

    private companion object {
        fun query(mode: FootMode, value: FootModeValue) =
            FootModeCommandExchangeResult.Response(
                FootModeResponse(mode, FootModeResponseKind.QUERY, value)
            )

        fun set(mode: FootMode, value: FootModeValue) =
            FootModeCommandExchangeResult.Response(
                FootModeResponse(mode, FootModeResponseKind.SET, value)
            )
    }
}
