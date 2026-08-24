package com.onomic.footpilot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootModeRetryTest {
    @Test fun modeRetryUsesSharedTenSecondCodeSetting() {
        assertEquals(10, BleRetryPolicy.retryDelaySeconds)
        assertEquals(1, BleRetryPolicy.ONE_SHOT_CONTROL_RETRIES)
    }

    @Test fun transientFailureSchedulesExactlyOneRetryWithoutRealDelay() = runBlocking {
        val attempts = mutableListOf<Int>()
        val seconds = mutableListOf<Int?>()
        var scheduled = 0
        var starting = 0
        val results = ArrayDeque<FootModeMutationAttemptResult>().apply {
            add(FootModeMutationAttemptResult.TransientFailure("connection failed"))
            add(transaction(verified = true))
        }

        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = seconds::add,
            onRetryScheduled = { scheduled++ },
            onRetryStarting = { starting++ },
            attempt = {
                attempts += attempts.size + 1
                results.removeFirst()
            }
        )

        assertEquals(2, result.attempts)
        assertEquals(listOf(1, 2), attempts)
        assertEquals(1, scheduled)
        assertEquals(1, starting)
        val expectedSeconds: List<Int?> =
            (BleRetryPolicy.retryDelaySeconds downTo 1).toList() + null
        assertEquals(expectedSeconds, seconds)
    }

    @Test fun everyAbsoluteModeTargetRetriesFromFreshQueryAndSkipsSatisfiedSet() = runBlocking {
        FootMode.entries.forEach { mode ->
            listOf(FootModeValue.ON, FootModeValue.OFF).forEach { target ->
                val initial = if (target == FootModeValue.ON) {
                    FootModeValue.OFF
                } else {
                    FootModeValue.ON
                }
                val first = RecordingTransport(
                    listOf(
                        response(mode, FootModeResponseKind.QUERY, initial),
                        FootModeCommandExchangeResult.ResponseMissing("SET response timed out"),
                        FootModeCommandExchangeResult.ResponseMissing("QUERY response timed out")
                    )
                )
                val second = RecordingTransport(
                    listOf(response(mode, FootModeResponseKind.QUERY, target))
                )
                val transports = ArrayDeque(listOf(first, second))

                val result = runner().run(
                    stillCurrent = { true },
                    publishSecondsRemaining = {},
                    onRetryScheduled = {},
                    onRetryStarting = {},
                    attempt = {
                        FootModeMutationAttemptResult.Transaction(
                            FootModeTransaction.execute(mode, target, transports.removeFirst())
                        )
                    }
                )

                assertEquals(2, result.attempts)
                assertEquals(3, first.commands.size)
                assertArrayEquals(FootModeProtocol.queryCommand(mode), first.commands[0])
                assertArrayEquals(FootModeProtocol.setCommand(mode, target), first.commands[1])
                assertArrayEquals(FootModeProtocol.queryCommand(mode), first.commands[2])
                assertEquals(1, second.commands.size)
                assertArrayEquals(FootModeProtocol.queryCommand(mode), second.commands.single())
                val final = result.finalAttempt as FootModeMutationAttemptResult.Transaction
                assertTrue(final.read.verified)
                assertFalse(final.read.setWriteAccepted)
            }
        }
    }

    @Test fun secondTransientFailureNeverSchedulesThirdAttempt() = runBlocking {
        var attempts = 0
        var scheduled = 0

        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { scheduled++ },
            onRetryStarting = {},
            attempt = {
                attempts++
                FootModeMutationAttemptResult.TransientFailure("failed")
            }
        )

        assertEquals(2, attempts)
        assertEquals(2, result.attempts)
        assertEquals(1, scheduled)
    }

    @Test fun coordinatorBusyDoesNotScheduleRetry() = runBlocking {
        var scheduled = false
        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { scheduled = true },
            onRetryStarting = {},
            attempt = { FootModeMutationAttemptResult.Busy }
        )

        assertEquals(1, result.attempts)
        assertFalse(scheduled)
    }

    @Test fun preconditionRejectionDoesNotScheduleRetry() = runBlocking {
        var attempts = 0
        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = {},
            onRetryStarting = {},
            attempt = {
                attempts++
                FootModeMutationAttemptResult.Rejected("Turn on Bluetooth")
            }
        )

        assertEquals(1, attempts)
        assertEquals(1, result.attempts)
    }

    @Test fun oppositeIntentSupersedesPendingRetryBeforeSecondAttempt() = runBlocking {
        val store = FootModeStateStore(
            FootModesState(targetAddress = "AA:BB:CC:DD:EE:FF")
        )
        val old = store.beginIntent(
            "AA:BB:CC:DD:EE:FF",
            FootMode.CHAIR_EXIT,
            FootModeValue.ON
        )
        var attempts = 0

        val result = runner().run(
            stillCurrent = { store.isCurrent(old) },
            publishSecondsRemaining = {},
            onRetryScheduled = {
                store.beginIntent(
                    "AA:BB:CC:DD:EE:FF",
                    FootMode.CHAIR_EXIT,
                    FootModeValue.OFF
                )
            },
            onRetryStarting = {},
            attempt = {
                attempts++
                FootModeMutationAttemptResult.TransientFailure("failed")
            }
        )

        assertEquals(1, attempts)
        assertTrue(result.superseded)
    }

    @Test fun selectedFootChangeInvalidatesPendingRetry() = runBlocking {
        val store = FootModeStateStore(
            FootModesState(targetAddress = "AA:BB:CC:DD:EE:FF")
        )
        val token = store.beginIntent(
            "AA:BB:CC:DD:EE:FF",
            FootMode.RELAX,
            FootModeValue.ON
        )
        var attempts = 0

        val result = runner().run(
            stillCurrent = { store.isCurrent(token) },
            publishSecondsRemaining = {},
            onRetryScheduled = { store.resetTarget("11:22:33:44:55:66") },
            onRetryStarting = {},
            attempt = {
                attempts++
                FootModeMutationAttemptResult.TransientFailure("failed")
            }
        )

        assertEquals(1, attempts)
        assertTrue(result.superseded)
    }

    @Test fun verifiedFirstAttemptDoesNotEnterCountdown() = runBlocking {
        var published = false
        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = { published = true },
            onRetryScheduled = {},
            onRetryStarting = {},
            attempt = { transaction(verified = true) }
        )

        assertEquals(1, result.attempts)
        assertFalse(published)
    }

    private fun runner() = FootModeOneShotRetry(
        LiveRetryCountdown(awaitTick = {})
    )

    private fun transaction(verified: Boolean): FootModeMutationAttemptResult.Transaction =
        FootModeMutationAttemptResult.Transaction(
            FootModeTransactionRead(
                mode = FootMode.RELAX,
                requested = FootModeValue.ON,
                verified = verified,
                finalValue = if (verified) FootModeValue.ON else null,
                ambiguous = !verified,
                setWriteAccepted = !verified,
                failure = if (verified) null else {
                    FootModeTransactionFailure.FINAL_QUERY_RESPONSE_MISSING
                },
                error = if (verified) null else "Not confirmed"
            )
        )

    private class RecordingTransport(
        results: List<FootModeCommandExchangeResult>
    ) : FootModeTransactionTransport {
        private val remaining = ArrayDeque(results)
        val commands = mutableListOf<ByteArray>()

        override suspend fun exchange(
            mode: FootMode,
            command: ByteArray,
            expectedKind: FootModeResponseKind,
            expectedValue: FootModeValue?
        ): FootModeCommandExchangeResult {
            commands += command.copyOf()
            return remaining.removeFirst()
        }
    }

    private fun response(
        mode: FootMode,
        kind: FootModeResponseKind,
        value: FootModeValue
    ) = FootModeCommandExchangeResult.Response(FootModeResponse(mode, kind, value))
}
