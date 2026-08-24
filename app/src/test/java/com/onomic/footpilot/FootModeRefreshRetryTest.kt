package com.onomic.footpilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FootModeRefreshRetryTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun firstRefreshSuccessStopsAfterOneAttempt() = runBlocking {
        var attempts = 0
        var retryScheduled = false

        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { retryScheduled = true },
            onRetryStarting = {},
            attempt = {
                attempts++
                FootModeRefreshAttemptResult.Success
            }
        )

        assertEquals(1, attempts)
        assertEquals(1, result.attempts)
        assertEquals(FootModeRefreshAttemptResult.Success, result.finalAttempt)
        assertFalse(retryScheduled)
    }

    @Test fun transientFailureCountsDownThenRetrySucceeds() = runBlocking {
        val results = ArrayDeque<FootModeRefreshAttemptResult>().apply {
            add(FootModeRefreshAttemptResult.TransientFailure("query timed out"))
            add(FootModeRefreshAttemptResult.Success)
        }
        val seconds = mutableListOf<Int?>()
        var scheduled = 0
        var starting = 0

        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = seconds::add,
            onRetryScheduled = { scheduled++ },
            onRetryStarting = { starting++ },
            attempt = { results.removeFirst() }
        )

        assertEquals(2, result.attempts)
        assertEquals(FootModeRefreshAttemptResult.Success, result.finalAttempt)
        assertEquals(1, scheduled)
        assertEquals(1, starting)
        assertEquals(
            (BleRetryPolicy.retryDelaySeconds downTo 1).toList() + null,
            seconds
        )
    }

    @Test fun secondTransientFailureStopsAfterExactlyTwoAttempts() = runBlocking {
        var attempts = 0
        var scheduled = 0

        val result = runner().run(
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = { scheduled++ },
            onRetryStarting = {},
            attempt = {
                attempts++
                FootModeRefreshAttemptResult.TransientFailure("attempt $attempts failed")
            }
        )

        assertEquals(2, attempts)
        assertEquals(2, result.attempts)
        assertEquals(1, scheduled)
        assertEquals(
            FootModeRefreshAttemptResult.TransientFailure("attempt 2 failed"),
            result.finalAttempt
        )
    }

    @Test fun rejectedAndBusyResultsDoNotRetry() = runBlocking {
        listOf(
            FootModeRefreshAttemptResult.Rejected("Turn on Bluetooth"),
            FootModeRefreshAttemptResult.Busy
        ).forEach { first ->
            var attempts = 0
            var scheduled = false

            val result = runner().run(
                stillCurrent = { true },
                publishSecondsRemaining = {},
                onRetryScheduled = { scheduled = true },
                onRetryStarting = {},
                attempt = {
                    attempts++
                    first
                }
            )

            assertEquals(1, attempts)
            assertEquals(1, result.attempts)
            assertFalse(scheduled)
        }
    }

    @Test fun selectedFootChangeBeforeRetryPreventsSecondAttempt() = runBlocking {
        var selectedTargetIsCurrent = true
        var attempts = 0
        val published = mutableListOf<Int?>()

        val result = runner().run(
            stillCurrent = { selectedTargetIsCurrent },
            publishSecondsRemaining = published::add,
            onRetryScheduled = { selectedTargetIsCurrent = false },
            onRetryStarting = {},
            attempt = {
                attempts++
                FootModeRefreshAttemptResult.TransientFailure("query timed out")
            }
        )

        assertEquals(1, attempts)
        assertTrue(result.superseded)
        assertEquals(listOf<Int?>(null), published)
    }

    @Test fun cancellationDuringCountdownClearsPublisherAndStartsNoRetry() = runBlocking {
        var attempts = 0
        val published = mutableListOf<Int?>()
        val cancellingRunner = FootModeRefreshOneShotRetry(
            LiveRetryCountdown(awaitTick = { throw CancellationException("cancelled") })
        )

        try {
            cancellingRunner.run(
                stillCurrent = { true },
                publishSecondsRemaining = published::add,
                onRetryScheduled = {},
                onRetryStarting = {},
                attempt = {
                    attempts++
                    FootModeRefreshAttemptResult.TransientFailure("query timed out")
                }
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation propagates to the owning refresh job.
        }

        assertEquals(1, attempts)
        assertEquals(listOf(BleRetryPolicy.retryDelaySeconds, null), published)
    }

    @Test fun refreshCountdownAndRetryAttemptUpdateBothRowsInline() {
        val store = historicalStore()
        assertTrue(store.beginRefresh(address))

        store.beginRefreshRetry(address)
        store.updateRefreshRetrySeconds(address, BleRetryPolicy.retryDelaySeconds)

        FootMode.entries.forEach { mode ->
            val status = store.state.value.status(mode)
            val presentation = FootModePresentation.create(
                mode,
                status,
                hasSelectedFoot = true,
                controlsAvailable = true
            )
            assertEquals(FootModeOperation.RETRY_WAIT, status.operation)
            assertFalse(status.currentConfirmed)
            assertEquals(BleRetryPolicy.retryDelaySeconds, status.retrySecondsRemaining)
            assertEquals("Retrying in 10s...", presentation.secondaryText)
            assertFalse(presentation.enabled)
        }

        store.beginRefreshRetryAttempt(address)

        FootMode.entries.forEach { mode ->
            val status = store.state.value.status(mode)
            assertEquals(FootModeOperation.CHECKING, status.operation)
            assertNull(status.retrySecondsRemaining)
            assertEquals("Checking...", status.message)
        }
    }

    @Test fun cancellationCleanupSettlesBothRowsWithoutPromotingHistoricalTruth() = runBlocking {
        val store = historicalStore()
        assertTrue(store.beginRefresh(address))
        val cancellingRunner = FootModeRefreshOneShotRetry(
            LiveRetryCountdown(awaitTick = { throw CancellationException("cancelled") })
        )

        try {
            cancellingRunner.run(
                stillCurrent = { true },
                publishSecondsRemaining = {
                    store.updateRefreshRetrySeconds(address, it)
                },
                onRetryScheduled = { store.beginRefreshRetry(address) },
                onRetryStarting = { store.beginRefreshRetryAttempt(address) },
                attempt = {
                    FootModeRefreshAttemptResult.TransientFailure("query timed out")
                }
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            store.failRefresh(address, "Foot mode check cancelled")
        }

        FootMode.entries.forEach { mode ->
            val status = store.state.value.status(mode)
            assertEquals(FootModeOperation.IDLE, status.operation)
            assertFalse(status.currentConfirmed)
            assertNull(status.retrySecondsRemaining)
            assertEquals("Foot mode check cancelled", status.message)
        }
        assertEquals(FootModeValue.ON, store.state.value.chairExit.lastVerified)
        assertEquals(FootModeValue.OFF, store.state.value.relax.lastVerified)
    }

    @Test fun finalRetryFailureSettlesBothRowsIdleAndUnconfirmed() {
        val store = historicalStore()
        assertTrue(store.beginRefresh(address))
        store.beginRefreshRetry(address)
        store.updateRefreshRetrySeconds(address, 1)
        store.beginRefreshRetryAttempt(address)

        store.failRefresh(address, "Could not verify foot modes")

        FootMode.entries.forEach { mode ->
            val status = store.state.value.status(mode)
            assertEquals(FootModeOperation.IDLE, status.operation)
            assertFalse(status.currentConfirmed)
            assertNull(status.retrySecondsRemaining)
            assertEquals("Could not verify foot modes", status.message)
        }
    }

    private fun runner() = FootModeRefreshOneShotRetry(
        LiveRetryCountdown(awaitTick = {})
    )

    private fun historicalStore() = FootModeStateStore(
        FootModesState(
            targetAddress = address,
            chairExit = FootModeStatus(
                lastVerified = FootModeValue.ON,
                currentConfirmed = true
            ),
            relax = FootModeStatus(
                lastVerified = FootModeValue.OFF,
                currentConfirmed = true
            )
        )
    )
}
