package com.onomic.footpilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StandbyRetryTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun standbyOnAndOffTransientFailuresEachScheduleOnePolicyDelay() = runBlocking {
        listOf(StandbyState.ON, StandbyState.OFF).forEach { target ->
            val requests = mutableListOf<StandbyAttemptRequest>()
            val seconds = mutableListOf<Int?>()
            var scheduled = 0

            val result = runner().run(
                initialRequest = StandbyAttemptRequest.Absolute(target),
                stillCurrent = { true },
                publishSecondsRemaining = seconds::add,
                onRetryScheduled = { _, _ -> scheduled++ },
                onRetryStarting = {},
                attempt = { request ->
                    requests += request
                    if (requests.size == 1) {
                        StandbyAttemptResult.TransientFailure("Connection failed")
                    } else {
                        StandbyAttemptResult.Transaction(verified(target))
                    }
                }
            )

            assertEquals(2, result.attempts)
            assertEquals(1, scheduled)
            assertEquals(
                listOf(
                    StandbyAttemptRequest.Absolute(target),
                    StandbyAttemptRequest.Absolute(target)
                ),
                requests
            )
            val expectedSeconds = mutableListOf<Int?>()
            expectedSeconds.addAll(BleRetryPolicy.retryDelaySeconds downTo 1)
            expectedSeconds += null
            assertEquals(expectedSeconds, seconds)
        }
    }

    @Test fun retryStartsOnlyAfterTheSharedCountdownCompletes() = runBlocking {
        val events = mutableListOf<String>()
        var attempts = 0

        runner().run(
            initialRequest = StandbyAttemptRequest.Absolute(StandbyState.ON),
            stillCurrent = { true },
            publishSecondsRemaining = { seconds -> events += "seconds-$seconds" },
            onRetryScheduled = { _, _ -> events += "scheduled" },
            onRetryStarting = { events += "starting" },
            attempt = {
                attempts++
                events += "attempt-$attempts"
                if (attempts == 1) {
                    StandbyAttemptResult.TransientFailure("failed")
                } else {
                    StandbyAttemptResult.Transaction(verified(StandbyState.ON))
                }
            }
        )

        assertEquals("attempt-1", events[0])
        assertEquals("scheduled", events[1])
        assertEquals("seconds-${BleRetryPolicy.retryDelaySeconds}", events[2])
        assertEquals("seconds-null", events[events.lastIndex - 2])
        assertEquals("starting", events[events.lastIndex - 1])
        assertEquals("attempt-2", events.last())
    }

    @Test fun ambiguousNotificationToggleRetainsResolvedOnTargetAndRetrySkipsSatisfiedSet() =
        runBlocking {
            val first = RecordingTransport(
                StandbyCommandExchangeResult.Response(query(StandbyState.OFF)),
                StandbyCommandExchangeResult.ResponseMissing("SET response timed out"),
                StandbyCommandExchangeResult.ResponseMissing("QUERY response timed out")
            )
            val second = RecordingTransport(
                StandbyCommandExchangeResult.Response(query(StandbyState.ON)),
                battery = StandbyBatteryReadResult.Success(72)
            )
            val transports = ArrayDeque(listOf(first, second))
            val requests = mutableListOf<StandbyAttemptRequest>()

            val result = runner().run(
                initialRequest = StandbyAttemptRequest.Toggle,
                stillCurrent = { true },
                publishSecondsRemaining = {},
                onRetryScheduled = { _, _ -> },
                onRetryStarting = {},
                attempt = { request ->
                    requests += request
                    StandbyAttemptResult.Transaction(execute(request, transports.removeFirst()))
                }
            )

            assertEquals(StandbyAttemptRequest.Toggle, requests[0])
            assertEquals(
                StandbyAttemptRequest.Absolute(StandbyState.ON),
                requests[1]
            )
            assertEquals(1, second.commands.size)
            assertArrayEquals(StandbyProtocol.queryCommand(), second.commands.single())
            val final = result.finalAttempt as StandbyAttemptResult.Transaction
            assertTrue(final.read.verified)
            assertFalse(final.read.setWriteAccepted)
        }

    @Test fun ambiguousNotificationToggleRetainsResolvedOffTarget() = runBlocking {
        val requests = mutableListOf<StandbyAttemptRequest>()
        var attempts = 0

        runner().run(
            initialRequest = StandbyAttemptRequest.Toggle,
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ -> },
            onRetryStarting = {},
            attempt = { request ->
                requests += request
                attempts++
                if (attempts == 1) {
                    StandbyAttemptResult.Transaction(
                        retryable(StandbyState.OFF, ambiguous = true)
                    )
                } else {
                    StandbyAttemptResult.Transaction(verified(StandbyState.OFF))
                }
            }
        )

        assertEquals(StandbyAttemptRequest.Toggle, requests[0])
        assertEquals(StandbyAttemptRequest.Absolute(StandbyState.OFF), requests[1])
    }

    @Test fun unresolvedNotificationTargetMayRetryAsFreshToggle() = runBlocking {
        val requests = mutableListOf<StandbyAttemptRequest>()
        var attempts = 0

        val result = runner().run(
            initialRequest = StandbyAttemptRequest.Toggle,
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ -> },
            onRetryStarting = {},
            attempt = { request ->
                requests += request
                attempts++
                if (attempts == 1) {
                    StandbyAttemptResult.Transaction(
                        StandbyTransactionRead(
                            requested = StandbyState.UNKNOWN,
                            verified = false,
                            finalState = null,
                            batteryLevel = null,
                            ambiguous = false,
                            error = "Could not verify standby",
                            failure = StandbyTransactionFailure.INITIAL_QUERY_RESPONSE_MISSING
                        )
                    )
                } else {
                    StandbyAttemptResult.Transaction(verified(StandbyState.ON))
                }
            }
        )

        assertEquals(listOf(StandbyAttemptRequest.Toggle, StandbyAttemptRequest.Toggle), requests)
        assertEquals(2, result.attempts)
    }

    @Test fun retryFreshQueryThatDiffersSendsExactAbsoluteSetAndFinalQuery() = runBlocking {
        val target = StandbyState.OFF
        val retryTransport = RecordingTransport(
            StandbyCommandExchangeResult.Response(query(StandbyState.ON)),
            StandbyCommandExchangeResult.Response(set(target)),
            StandbyCommandExchangeResult.Response(query(target)),
            battery = StandbyBatteryReadResult.Success(71)
        )
        var attempts = 0

        val result = runner().run(
            initialRequest = StandbyAttemptRequest.Absolute(target),
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ -> },
            onRetryStarting = {},
            attempt = { request ->
                attempts++
                if (attempts == 1) {
                    StandbyAttemptResult.TransientFailure("Connection failed")
                } else {
                    StandbyAttemptResult.Transaction(execute(request, retryTransport))
                }
            }
        )

        assertArrayEquals(StandbyProtocol.queryCommand(), retryTransport.commands[0])
        assertArrayEquals(StandbyProtocol.setCommand(target), retryTransport.commands[1])
        assertArrayEquals(StandbyProtocol.queryCommand(), retryTransport.commands[2])
        assertTrue((result.finalAttempt as StandbyAttemptResult.Transaction).read.verified)
    }

    @Test fun finalRetryQueryRemainsAuthoritativeAndNeverSchedulesThirdAttempt() = runBlocking {
        val retryTransport = RecordingTransport(
            StandbyCommandExchangeResult.Response(query(StandbyState.OFF)),
            StandbyCommandExchangeResult.ResponseMissing("SET response timed out"),
            StandbyCommandExchangeResult.Response(query(StandbyState.OFF)),
            battery = StandbyBatteryReadResult.Success(70)
        )
        var attempts = 0
        var scheduled = 0

        val result = runner().run(
            initialRequest = StandbyAttemptRequest.Absolute(StandbyState.ON),
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ -> scheduled++ },
            onRetryStarting = {},
            attempt = { request ->
                attempts++
                if (attempts == 1) {
                    StandbyAttemptResult.TransientFailure("Connection failed")
                } else {
                    StandbyAttemptResult.Transaction(execute(request, retryTransport))
                }
            }
        )

        val final = (result.finalAttempt as StandbyAttemptResult.Transaction).read
        assertEquals(2, attempts)
        assertEquals(1, scheduled)
        assertFalse(final.verified)
        assertEquals(StandbyState.OFF, final.finalState)
        assertEquals(StandbyTransactionFailure.FINAL_STATE_MISMATCH, final.failure)
    }

    @Test fun secondTransientFailureNeverSchedulesThirdAttempt() = runBlocking {
        var attempts = 0
        var scheduled = 0

        val result = runner().run(
            initialRequest = StandbyAttemptRequest.Absolute(StandbyState.ON),
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ -> scheduled++ },
            onRetryStarting = {},
            attempt = {
                attempts++
                StandbyAttemptResult.TransientFailure("failed")
            }
        )

        assertEquals(2, result.attempts)
        assertEquals(2, attempts)
        assertEquals(1, scheduled)
    }

    @Test fun rejectionAndCoordinatorBusyDoNotRetry() = runBlocking {
        listOf(
            StandbyAttemptResult.Rejected("Turn on Bluetooth"),
            StandbyAttemptResult.Busy
        ).forEach { first ->
            var attempts = 0
            var scheduled = false
            val result = runner().run(
                initialRequest = StandbyAttemptRequest.Absolute(StandbyState.ON),
                stillCurrent = { true },
                publishSecondsRemaining = {},
                onRetryScheduled = { _, _ -> scheduled = true },
                onRetryStarting = {},
                attempt = {
                    attempts++
                    first
                }
            )

            assertEquals(1, result.attempts)
            assertEquals(1, attempts)
            assertFalse(scheduled)
        }
    }

    @Test fun cancellationDuringWaitDoesNotStartRetry() = runBlocking {
        var attempts = 0
        val cancellingRunner = StandbyOneShotRetry(
            LiveRetryCountdown(awaitTick = { throw CancellationException("cancelled") })
        )

        try {
            cancellingRunner.run(
                initialRequest = StandbyAttemptRequest.Absolute(StandbyState.ON),
                stillCurrent = { true },
                publishSecondsRemaining = {},
                onRetryScheduled = { _, _ -> },
                onRetryStarting = {},
                attempt = {
                    attempts++
                    StandbyAttemptResult.TransientFailure("failed")
                }
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation propagates instead of becoming a retry classification.
        }

        assertEquals(1, attempts)
    }

    @Test fun selectedFootInvalidationAndNewOppositeIntentEachCancelOldRetry() = runBlocking {
        val generation = StandbyRequestGeneration()
        val old = generation.begin(
            address,
            StandbyAttemptRequest.Absolute(StandbyState.ON)
        )
        var attempts = 0

        val superseded = runner().run(
            initialRequest = old.initialRequest,
            stillCurrent = { generation.isCurrent(old) },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ ->
                generation.begin(
                    address,
                    StandbyAttemptRequest.Absolute(StandbyState.OFF)
                )
            },
            onRetryStarting = {},
            attempt = {
                attempts++
                StandbyAttemptResult.TransientFailure("failed")
            }
        )

        assertTrue(superseded.superseded)
        assertEquals(1, attempts)

        val selectedFootToken = generation.begin(
            address,
            StandbyAttemptRequest.Absolute(StandbyState.ON)
        )
        attempts = 0
        val selectedFootChanged = runner().run(
            initialRequest = selectedFootToken.initialRequest,
            stillCurrent = { generation.isCurrent(selectedFootToken) },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ -> generation.invalidate() },
            onRetryStarting = {},
            attempt = {
                attempts++
                StandbyAttemptResult.TransientFailure("failed")
            }
        )

        assertTrue(selectedFootChanged.superseded)
        assertEquals(1, attempts)
    }

    @Test fun verifiedStandbyWithBatteryOnlyFailureDoesNotRetry() = runBlocking {
        val transport = RecordingTransport(
            StandbyCommandExchangeResult.Response(query(StandbyState.ON)),
            battery = StandbyBatteryReadResult.Failed("Battery check failed")
        )
        val read = StandbyTransaction.execute(StandbyState.ON, transport)
        var attempts = 0

        val result = runner().run(
            initialRequest = StandbyAttemptRequest.Absolute(StandbyState.ON),
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { _, _ -> },
            onRetryStarting = {},
            attempt = {
                attempts++
                StandbyAttemptResult.Transaction(read)
            }
        )

        assertTrue(read.verified)
        assertEquals("Battery check failed", read.batteryError)
        assertEquals(null, read.failure)
        assertEquals(1, attempts)
        assertEquals(1, result.attempts)
    }

    private fun runner() = StandbyOneShotRetry(
        countdown = LiveRetryCountdown(awaitTick = {})
    )

    private suspend fun execute(
        request: StandbyAttemptRequest,
        transport: StandbyTransactionTransport
    ): StandbyTransactionRead = when (request) {
        is StandbyAttemptRequest.Absolute ->
            StandbyTransaction.execute(request.requested, transport)
        StandbyAttemptRequest.Toggle -> StandbyTransaction.executeToggle(transport)
    }

    private fun verified(target: StandbyState) = StandbyTransactionRead(
        requested = target,
        verified = true,
        finalState = target,
        batteryLevel = 75,
        ambiguous = false,
        error = null
    )

    private fun retryable(
        target: StandbyState,
        ambiguous: Boolean = false
    ) = StandbyTransactionRead(
        requested = target,
        verified = false,
        finalState = null,
        batteryLevel = null,
        ambiguous = ambiguous,
        error = "Not confirmed",
        setWriteAccepted = ambiguous,
        failure = StandbyTransactionFailure.FINAL_QUERY_RESPONSE_MISSING
    )

    private class RecordingTransport(
        vararg results: StandbyCommandExchangeResult,
        private val battery: StandbyBatteryReadResult =
            StandbyBatteryReadResult.Failed("Battery should not be read")
    ) : StandbyTransactionTransport {
        private val remaining = ArrayDeque(results.toList())
        val commands = mutableListOf<ByteArray>()

        override suspend fun exchange(
            command: ByteArray,
            expectedKind: StandbyResponseKind,
            expectedState: StandbyState?
        ): StandbyCommandExchangeResult {
            commands += command.copyOf()
            return remaining.removeFirst()
        }

        override suspend fun readBattery(): StandbyBatteryReadResult = battery
    }

    private fun query(state: StandbyState) =
        StandbyResponse(StandbyResponseKind.QUERY, state)

    private fun set(state: StandbyState) =
        StandbyResponse(StandbyResponseKind.SET, state)
}
