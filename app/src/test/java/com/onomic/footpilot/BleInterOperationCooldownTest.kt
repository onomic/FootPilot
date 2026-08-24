package com.onomic.footpilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleInterOperationCooldownTest {
    private val footA = "AA:BB:CC:DD:EE:FF"
    private val footB = "11:22:33:44:55:66"

    @Test fun noPriorReleaseHasZeroRemainingDelay() {
        val fixture = Fixture(nowMs = 1_000L)

        assertEquals(0L, fixture.cooldown.remainingMs(footA))
        assertTrue(fixture.cooldown.isReady(footA))
    }

    @Test fun immediateRequestAfterReleaseHasFullQuietPeriodRemaining() {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted(footA)

        assertEquals(BleInterOperationPolicy.QUIET_PERIOD_MS, fixture.cooldown.remainingMs(footA))
    }

    @Test fun twelveHundredMillisecondsElapsedLeavesTwentyEightHundred() {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted(footA)
        fixture.nowMs += 1_200L

        assertEquals(2_800L, fixture.cooldown.remainingMs(footA))
    }

    @Test fun exactlyFourSecondsElapsedIsReady() {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted(footA)
        fixture.nowMs += BleInterOperationPolicy.QUIET_PERIOD_MS

        assertEquals(0L, fixture.cooldown.remainingMs(footA))
        assertTrue(fixture.cooldown.isReady(footA))
    }

    @Test fun moreThanFourSecondsElapsedIsReady() {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted(footA)
        fixture.nowMs += BleInterOperationPolicy.QUIET_PERIOD_MS + 1_000L

        assertEquals(0L, fixture.cooldown.remainingMs(footA))
    }

    @Test fun remainingDurationNeverBecomesNegative() {
        assertEquals(
            0L,
            remainingQuietPeriodMs(releaseCompletedAt = 1_000L, nowMs = Long.MAX_VALUE)
        )
        assertEquals(
            BleInterOperationPolicy.QUIET_PERIOD_MS,
            remainingQuietPeriodMs(releaseCompletedAt = 2_000L, nowMs = 1_000L)
        )
    }

    @Test fun releaseForFootADoesNotDelayFootB() {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted(footA)

        assertEquals(BleInterOperationPolicy.QUIET_PERIOD_MS, fixture.cooldown.remainingMs(footA))
        assertEquals(0L, fixture.cooldown.remainingMs(footB))
    }

    @Test fun addressNormalizationUsesOneLogicalTarget() {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted("  aa:bb:cc:dd:ee:ff  ")
        fixture.nowMs += 1_000L
        fixture.cooldown.recordReleaseCompleted(footA)
        fixture.nowMs += 500L

        assertEquals(3_500L, fixture.cooldown.remainingMs("aa:bb:cc:dd:ee:ff"))
        assertEquals(3_500L, fixture.cooldown.remainingMs("  AA:BB:CC:DD:EE:FF "))
    }

    @Test fun newerReleaseCompletionResetsDeadline() {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted(footA)
        fixture.nowMs += 2_500L
        assertEquals(1_500L, fixture.cooldown.remainingMs(footA))

        fixture.cooldown.recordReleaseCompleted(footA)

        assertEquals(BleInterOperationPolicy.QUIET_PERIOD_MS, fixture.cooldown.remainingMs(footA))
    }

    @Test fun awaitReadySleepsOnlyCalculatedRemainder() = runBlocking {
        val fixture = Fixture(nowMs = 1_000L)
        fixture.cooldown.recordReleaseCompleted(footA)
        fixture.nowMs += 1_200L

        fixture.cooldown.awaitReady(footA)

        assertEquals(listOf(2_800L), fixture.sleeps)
        assertEquals(0L, fixture.cooldown.remainingMs(footA))
    }

    @Test fun awaitReadyDoesNotSleepWhenTargetIsAlreadyReady() = runBlocking {
        val fixture = Fixture(nowMs = 1_000L)

        fixture.cooldown.awaitReady(footA)

        assertTrue(fixture.sleeps.isEmpty())
    }

    @Test fun cancellationDuringWaitPreventsSubsequentConnectWork() = runBlocking {
        var connectStarted = false
        var nowMs = 1_000L
        val cooldown = TargetScopedBleInterOperationCooldown(
            clock = { nowMs },
            sleeper = { throw CancellationException("cancelled during quiet period") }
        )
        cooldown.recordReleaseCompleted(footA)
        nowMs += 500L

        try {
            cooldown.awaitReady(footA)
            connectStarted = true
        } catch (_: CancellationException) {
            // Expected: production delay is cancellable and control never advances to connect.
        }

        assertFalse(connectStarted)
        assertEquals(3_500L, cooldown.remainingMs(footA))
    }

    private class Fixture(nowMs: Long) {
        var nowMs = nowMs
        val sleeps = mutableListOf<Long>()
        val cooldown = TargetScopedBleInterOperationCooldown(
            clock = { this.nowMs },
            sleeper = { durationMs ->
                sleeps += durationMs
                this.nowMs += durationMs
            }
        )
    }
}
