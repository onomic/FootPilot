package com.example.footbattery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyTransactionTest {
    @Test fun notificationToggleDerivesTargetFromFreshFootState() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.ON)),
                notifications(set(StandbyState.OFF)),
                notifications(query(StandbyState.OFF))
            ),
            battery = StandbyBatteryReadResult.Success(83)
        )

        val result = StandbyTransaction.executeToggle(transport)

        assertTrue(result.verified)
        assertEquals(StandbyState.OFF, result.requested)
        assertEquals(StandbyState.OFF, result.finalState)
        assertArrayEquals(StandbyProtocol.queryCommand(), transport.commands[0])
        assertArrayEquals(StandbyProtocol.setCommand(StandbyState.OFF), transport.commands[1])
        assertArrayEquals(StandbyProtocol.queryCommand(), transport.commands[2])
    }

    @Test fun initialQueryAlreadyRequestedSkipsSetAndFinalQuery() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(notifications(query(StandbyState.ON))),
            battery = StandbyBatteryReadResult.Success(82)
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertTrue(result.verified)
        assertFalse(result.setWriteAccepted)
        assertFalse(result.ambiguous)
        assertEquals(StandbyState.ON, result.finalState)
        assertEquals(82, result.batteryLevel)
        assertEquals(null, result.error)
        assertEquals(listOf(StandbyResponseKind.QUERY), transport.expectedKinds)
        assertEquals(1, transport.batteryReads)

        val reduction = SnapshotReducer.reduce(
            SnapshotState(70, StandbyState.OFF, 100L),
            SnapshotEvent.StandbyChange(
                requested = StandbyState.ON,
                finalState = result.finalState,
                verified = result.verified,
                batteryLevel = result.batteryLevel,
                checkedAt = 200L,
                ambiguous = result.ambiguous
            )
        )
        assertEquals(SnapshotState(82, StandbyState.ON, 200L), reduction.snapshot)
        assertTrue(reduction.completeSnapshotSaved)
    }

    @Test fun initialQueryAlreadyRequestedStillReturnsBatteryFailure() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(notifications(query(StandbyState.OFF))),
            battery = StandbyBatteryReadResult.Failed("Battery check failed")
        )

        val result = StandbyTransaction.execute(StandbyState.OFF, transport)

        assertTrue(result.verified)
        assertFalse(result.setWriteAccepted)
        assertFalse(result.ambiguous)
        assertEquals(StandbyState.OFF, result.finalState)
        assertEquals(null, result.batteryLevel)
        assertEquals("Battery check failed", result.batteryError)
        assertEquals(listOf(StandbyResponseKind.QUERY), transport.expectedKinds)
        assertEquals(1, transport.batteryReads)
    }

    @Test fun acceptedWriteSetResponseAndFinalQueryVerifyRequestedState() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                notifications(set(StandbyState.ON)),
                notifications(query(StandbyState.ON))
            ),
            battery = StandbyBatteryReadResult.Success(81)
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertTrue(result.verified)
        assertTrue(result.setWriteAccepted)
        assertEquals(StandbyState.ON, result.finalState)
        assertEquals(81, result.batteryLevel)
        assertEquals(1, transport.batteryReads)
        assertEquals(
            listOf(StandbyResponseKind.QUERY, StandbyResponseKind.SET, StandbyResponseKind.QUERY),
            transport.expectedKinds
        )
    }

    @Test fun acceptedWriteSetTimeoutAndFinalQueryVerifyRequestedState() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                result(StandbyCommandExchangeResult.ResponseMissing("response timed out")),
                notifications(query(StandbyState.ON))
            ),
            battery = StandbyBatteryReadResult.Success(80)
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertTrue(result.verified)
        assertTrue(result.setWriteAccepted)
        assertFalse(result.ambiguous)
        assertEquals(StandbyState.ON, result.finalState)
    }

    @Test fun delayedSetResponseCannotSatisfyFinalQuery() = runBlocking {
        val delayedSet = set(StandbyState.ON)
        val finalQuery = query(StandbyState.ON)
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                result(StandbyCommandExchangeResult.ResponseMissing("response timed out")),
                notifications(delayedSet, finalQuery)
            ),
            battery = StandbyBatteryReadResult.Success(79)
        )

        val transaction = StandbyTransaction.execute(StandbyState.ON, transport)

        assertTrue(transaction.verified)
        assertEquals(StandbyResponseKind.QUERY, transport.selectedResponses.last().kind)
        assertEquals(finalQuery, transport.selectedResponses.last())
    }

    @Test fun finalQueryOppositeStateStillReadsBattery() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                result(StandbyCommandExchangeResult.ResponseMissing("response timed out")),
                notifications(query(StandbyState.OFF))
            ),
            battery = StandbyBatteryReadResult.Success(78)
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertFalse(result.verified)
        assertFalse(result.ambiguous)
        assertEquals(StandbyState.OFF, result.finalState)
        assertEquals(78, result.batteryLevel)
        assertEquals("Foot remained standby off", result.error)
        assertEquals(1, transport.batteryReads)
    }

    @Test fun acceptedWriteAndFinalQueryTimeoutBecomeAmbiguous() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                result(StandbyCommandExchangeResult.ResponseMissing("SET response timed out")),
                result(StandbyCommandExchangeResult.ResponseMissing("QUERY response timed out"))
            )
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertFalse(result.verified)
        assertTrue(result.setWriteAccepted)
        assertTrue(result.ambiguous)
        assertEquals(null, result.finalState)
        assertEquals(0, transport.batteryReads)
        assertEquals(
            "Standby not confirmed: state could not be verified after command",
            result.error
        )
    }

    @Test fun rejectedSetWritePreservesNonAmbiguousFailure() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                result(StandbyCommandExchangeResult.WriteFailed("Android rejected the write"))
            )
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertFalse(result.setWriteAccepted)
        assertFalse(result.ambiguous)
        assertEquals(null, result.finalState)
        assertEquals(0, transport.batteryReads)
        assertTrue(result.error.orEmpty().startsWith("Standby command write failed"))
    }

    @Test fun setGattCallbackFailureIsAWriteFailureNotAmbiguity() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                result(StandbyCommandExchangeResult.WriteFailed("Bluetooth operation failed (133)"))
            )
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertFalse(result.setWriteAccepted)
        assertFalse(result.ambiguous)
        assertEquals(
            "Standby command write failed: Bluetooth operation failed (133)",
            result.error
        )
    }

    @Test fun knownFinalStateWithBatteryFailureIsStillReturned() = runBlocking {
        val transport = FakeTransport(
            exchangeSteps = listOf(
                notifications(query(StandbyState.OFF)),
                notifications(set(StandbyState.ON)),
                notifications(query(StandbyState.OFF))
            ),
            battery = StandbyBatteryReadResult.Failed("Battery check failed")
        )

        val result = StandbyTransaction.execute(StandbyState.ON, transport)

        assertEquals(StandbyState.OFF, result.finalState)
        assertEquals(null, result.batteryLevel)
        assertEquals("Battery check failed", result.batteryError)
        assertEquals(1, transport.batteryReads)
    }

    private sealed interface ExchangeStep {
        data class Notifications(val responses: List<StandbyResponse>) : ExchangeStep
        data class Result(val value: StandbyCommandExchangeResult) : ExchangeStep
    }

    private class FakeTransport(
        exchangeSteps: List<ExchangeStep>,
        private val battery: StandbyBatteryReadResult =
            StandbyBatteryReadResult.Failed("Battery should not be read")
    ) : StandbyTransactionTransport {
        private val steps = ArrayDeque(exchangeSteps)
        val commands = mutableListOf<ByteArray>()
        val expectedKinds = mutableListOf<StandbyResponseKind>()
        val selectedResponses = mutableListOf<StandbyResponse>()
        var batteryReads = 0

        override suspend fun exchange(
            command: ByteArray,
            expectedKind: StandbyResponseKind,
            expectedState: StandbyState?
        ): StandbyCommandExchangeResult {
            commands += command.copyOf()
            expectedKinds += expectedKind
            return when (val step = steps.removeFirst()) {
                is ExchangeStep.Result -> step.value
                is ExchangeStep.Notifications -> {
                    val selected = step.responses.firstOrNull {
                        StandbyProtocol.matches(it, expectedKind, expectedState)
                    } ?: return StandbyCommandExchangeResult.ResponseMissing("response timed out")
                    selectedResponses += selected
                    StandbyCommandExchangeResult.Response(selected)
                }
            }
        }

        override suspend fun readBattery(): StandbyBatteryReadResult {
            batteryReads++
            return battery
        }
    }

    private fun notifications(vararg responses: StandbyResponse): ExchangeStep =
        ExchangeStep.Notifications(responses.toList())

    private fun result(value: StandbyCommandExchangeResult): ExchangeStep =
        ExchangeStep.Result(value)

    private fun query(state: StandbyState) =
        StandbyResponse(StandbyResponseKind.QUERY, state)

    private fun set(state: StandbyState) =
        StandbyResponse(StandbyResponseKind.SET, state)
}
