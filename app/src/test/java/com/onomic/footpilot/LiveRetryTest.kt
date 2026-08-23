package com.onomic.footpilot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRetryTest {
    @Test fun retryIntervalIsFixedAtFifteenSeconds() {
        assertEquals(15_000L, BleRetryPolicy.RETRY_DELAY_MS)
        assertEquals(15, BleRetryPolicy.retryDelaySeconds)
    }

    @Test fun selectedFootResetClearsRetryCountdownState() {
        BatteryRepo.retrySecondsRemaining.value = 7

        BatteryRepo.resetForFootChange(hasSelectedFoot = true)

        assertEquals(null, BatteryRepo.retrySecondsRemaining.value)
        assertEquals(LiveConnectionState.IDLE, BatteryRepo.connectionState.value)
    }

    @Test fun initialPersistentAttemptIsImmediate() = runBlocking {
        val events = mutableListOf<String>()

        runPersistentLiveConnection(
            stillRequested = { true },
            attempt = {
                events += "attempt"
                LiveConnectionAttemptResult.Stopped
            },
            awaitRetry = {
                events += "retry"
                true
            },
            coordinatorYield = {}
        )

        assertEquals(listOf("attempt"), events)
    }

    @Test fun failedAttemptCountsDownFifteenThroughOneBeforeNextAttempt() = runBlocking {
        val events = mutableListOf<String>()
        var requested = true
        var attempts = 0
        var visibleSeconds: Int? = null
        val countdown = LiveRetryCountdown(awaitTick = {})

        runPersistentLiveConnection(
            stillRequested = { requested },
            attempt = {
                attempts++
                events += "attempt-$attempts"
                if (attempts == 1) {
                    LiveConnectionAttemptResult.Failed
                } else {
                    assertEquals(null, visibleSeconds)
                    requested = false
                    LiveConnectionAttemptResult.Stopped
                }
            },
            awaitRetry = {
                countdown.awaitRetry(
                    stillRequested = { requested },
                    publishSecondsRemaining = { seconds ->
                        visibleSeconds = seconds
                        if (seconds != null) events += "retry-$seconds"
                    }
                )
            },
            coordinatorYield = {}
        )

        assertEquals(
            listOf("attempt-1") +
                (BleRetryPolicy.retryDelaySeconds downTo 1).map { "retry-$it" } +
                "attempt-2",
            events
        )
    }

    @Test fun countdownPublishesNullAfterCompleting() = runBlocking {
        val published = mutableListOf<Int?>()

        val completed = LiveRetryCountdown(awaitTick = {}).awaitRetry(
            stillRequested = { true },
            publishSecondsRemaining = { published += it }
        )

        assertTrue(completed)
        val expected = mutableListOf<Int?>()
        expected.addAll(BleRetryPolicy.retryDelaySeconds downTo 1)
        expected += null
        assertEquals(expected, published)
    }

    @Test fun unexpectedDisconnectAfterReadyUsesTheSameCountdown() = runBlocking {
        val events = mutableListOf<String>()
        var requested = true
        var attempts = 0
        val countdown = LiveRetryCountdown(awaitTick = {})

        runPersistentLiveConnection(
            stillRequested = { requested },
            attempt = {
                attempts++
                events += "attempt-$attempts"
                if (attempts == 1) {
                    LiveConnectionAttemptResult.Ready {
                        events += "unexpected-disconnect"
                    }
                } else {
                    requested = false
                    LiveConnectionAttemptResult.Stopped
                }
            },
            awaitRetry = {
                countdown.awaitRetry(
                    stillRequested = { requested },
                    publishSecondsRemaining = { seconds ->
                        if (seconds != null) events += "retry-$seconds"
                    }
                )
            },
            coordinatorYield = {}
        )

        assertEquals("attempt-1", events.first())
        assertEquals("unexpected-disconnect", events[1])
        assertEquals("retry-15", events[2])
        assertEquals("retry-1", events[16])
        assertEquals("attempt-2", events.last())
    }

    @Test fun coordinatorBusyYieldsWithoutStartingRetryCountdown() = runBlocking {
        val events = mutableListOf<String>()
        var attempts = 0

        runPersistentLiveConnection(
            stillRequested = { true },
            attempt = {
                attempts++
                events += "attempt-$attempts"
                if (attempts == 1) {
                    LiveConnectionAttemptResult.Busy
                } else {
                    LiveConnectionAttemptResult.Stopped
                }
            },
            awaitRetry = {
                events += "retry"
                true
            },
            coordinatorYield = { events += "coordinator-yield" }
        )

        assertEquals(listOf("attempt-1", "coordinator-yield", "attempt-2"), events)
    }

    @Test fun releaseBarrierWaitingInsideAttemptDoesNotStartRetryCountdown() = runBlocking {
        val events = mutableListOf<String>()

        runPersistentLiveConnection(
            stillRequested = { true },
            attempt = {
                events += "release-barrier-wait"
                events += "attempt-stopped"
                LiveConnectionAttemptResult.Stopped
            },
            awaitRetry = {
                events += "retry"
                true
            },
            coordinatorYield = {}
        )

        assertEquals(listOf("release-barrier-wait", "attempt-stopped"), events)
    }

    @Test fun cancellationDuringCountdownClearsStateImmediately() = runBlocking {
        val published = mutableListOf<Int?>()
        val firstSecondPublished = CompletableDeferred<Unit>()
        val keepWaiting = CompletableDeferred<Unit>()
        val job = launch {
            LiveRetryCountdown(awaitTick = { keepWaiting.await() }).awaitRetry(
                stillRequested = { true },
                publishSecondsRemaining = {
                    published += it
                    if (it == BleRetryPolicy.retryDelaySeconds) firstSecondPublished.complete(Unit)
                }
            )
        }

        firstSecondPublished.await()
        job.cancelAndJoin()

        assertEquals(listOf(BleRetryPolicy.retryDelaySeconds, null), published)
    }

    @Test fun generationChangeStopsCountdownAndClearsState() = runBlocking {
        var generation = 7
        val expectedGeneration = generation
        val published = mutableListOf<Int?>()
        var ticks = 0

        val completed = LiveRetryCountdown(
            awaitTick = {
                ticks++
                if (ticks == 2) generation++
            }
        ).awaitRetry(
            stillRequested = { generation == expectedGeneration },
            publishSecondsRemaining = { published += it }
        )

        assertFalse(completed)
        assertEquals(listOf(15, 14, null), published)
    }

    @Test fun duplicateDisconnectSignalsProduceOneRetryAndOneNextAttempt() = runBlocking {
        val signal = LiveDisconnectSignal()
        assertTrue(signal.signal())
        assertFalse(signal.signal())
        var requested = true
        var attempts = 0
        var retries = 0

        runPersistentLiveConnection(
            stillRequested = { requested },
            attempt = {
                attempts++
                if (attempts == 1) {
                    LiveConnectionAttemptResult.Ready { signal.await() }
                } else {
                    requested = false
                    LiveConnectionAttemptResult.Stopped
                }
            },
            awaitRetry = {
                retries++
                true
            },
            coordinatorYield = {}
        )

        assertEquals(1, retries)
        assertEquals(2, attempts)
    }

    @Test fun retryLoopNeverOverlapsCountdownAndConnectAttemptOwners() = runBlocking {
        var requested = true
        var attempts = 0
        var activeAttempts = 0
        var activeCountdowns = 0
        var maxActiveAttempts = 0
        var maxActiveCountdowns = 0

        runPersistentLiveConnection(
            stillRequested = { requested },
            attempt = {
                assertEquals(0, activeCountdowns)
                activeAttempts++
                maxActiveAttempts = maxOf(maxActiveAttempts, activeAttempts)
                attempts++
                activeAttempts--
                if (attempts == 1) {
                    LiveConnectionAttemptResult.Failed
                } else {
                    requested = false
                    LiveConnectionAttemptResult.Stopped
                }
            },
            awaitRetry = {
                assertEquals(0, activeAttempts)
                activeCountdowns++
                maxActiveCountdowns = maxOf(maxActiveCountdowns, activeCountdowns)
                activeCountdowns--
                true
            },
            coordinatorYield = {}
        )

        assertEquals(1, maxActiveAttempts)
        assertEquals(1, maxActiveCountdowns)
        assertEquals(2, attempts)
    }
}
