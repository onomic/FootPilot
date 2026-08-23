package com.onomic.footpilot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleInterOperationIntegrationSafetyTest {
    private val cooldown by lazy {
        source("app/src/main/java/com/onomic/footpilot/BleInterOperationCooldown.kt")
    }
    private val operations by lazy {
        source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
    }
    private val releaseBarrier by lazy {
        source("app/src/main/java/com/onomic/footpilot/BleTargetReleaseBarrier.kt")
    }
    private val liveConnection by lazy {
        source("app/src/main/java/com/onomic/footpilot/LiveConnection.kt")
    }

    @Test fun policyHasOneExplicitThreeSecondMonotonicClockSource() {
        assertTrue(cooldown.contains("const val QUIET_PERIOD_MS = 3_000L"))
        assertEquals(1, cooldown.count("3_000L"))
        assertTrue(cooldown.contains("SystemClock::elapsedRealtime"))
        assertFalse(cooldown.contains("System.currentTimeMillis"))
    }

    @Test fun everyTemporaryHelperWaitsBeforeConstructingItsGatt() {
        val generic = operations.between(
            "private suspend fun withTemporarySession(",
            "private fun executionPrerequisiteError("
        )
        val standby = operations.between(
            "private suspend fun withTemporaryStandbySession(",
            "private fun applyStandbyAttempt("
        )
        val footMode = operations.between(
            "private suspend fun <T> withTemporaryFootModeSession(",
            "private fun modeExecutionPrerequisiteError("
        )

        listOf(generic, standby, footMode).forEach { helper ->
            assertBefore(
                helper,
                "BleInterOperationCooldown.awaitReady(target.address)",
                "FootGattSession(ctx, target)"
            )
            assertTrue(helper.contains("BleTargetReleaseBarrier.releaseTemporarySession"))
            assertTrue(helper.contains("withContext(NonCancellable)"))
        }
        assertEquals(3, operations.count("BleInterOperationCooldown.awaitReady(target.address)"))
    }

    @Test fun temporaryReleaseRecordsCompletionBeforePublishingTerminalGeneration() {
        val temporaryRelease = releaseBarrier.between(
            "suspend fun releaseTemporarySession(",
            "/** Called from the live coroutine"
        )

        assertBefore(
            temporaryRelease,
            "BleInterOperationCooldown.recordReleaseCompleted(token.address)",
            "releases.complete(token, outcome)"
        )
    }

    @Test fun explicitLiveDisconnectRecordsAfterTargetReleaseWork() {
        val stop = liveConnection.substringAfter("fun stop()")
            .substringBefore("private fun debug(")

        assertBefore(stop, "current?.disconnectAndClose", "BleRegistry.closeAll()")
        assertBefore(stop, "BleRegistry.closeAll()", "BondHelper.forceUnbond(app, it)")
        assertBefore(
            stop,
            "BondHelper.forceUnbond(app, it)",
            "BleInterOperationCooldown.recordReleaseCompleted(it.address)"
        )
    }

    @Test fun livePreflightRetainsGenerationRecoveryThenWaitsForCooldown() {
        val preflight = releaseBarrier.substringAfter("suspend fun awaitLiveConnectReady")
            .substringBefore("private fun debug(")

        assertTrue(preflight.contains("releases.awaitLatest(target.address)"))
        assertTrue(preflight.contains("releases.claimUncertainRecovery(released)"))
        assertTrue(preflight.contains("BleRegistry.closeTarget(target.address)"))
        assertBefore(
            preflight,
            "BleInterOperationCooldown.recordReleaseCompleted(target.address)",
            "BleInterOperationCooldown.awaitReady(target.address)"
        )

        val connect = liveConnection.substringAfter("private suspend fun connectOnce(")
            .substringBefore("private fun beginConnectAttempt()")
        assertBefore(
            connect,
            "BleTargetReleaseBarrier.awaitLiveConnectReady(",
            "created = FootGattSession("
        )
        assertTrue(connect.contains("if (!stillRequested(expectedGeneration, target))"))
    }

    @Test fun liveReadyBranchesBypassTheTemporaryCooldownHelpers() {
        val beforeGenericHelper = operations.substringBefore(
            "private suspend fun withTemporarySession("
        )

        assertTrue(beforeGenericHelper.contains("LiveConnection.readySession()"))
        assertTrue(beforeGenericHelper.contains("live != null -> readAndApplyOnSession"))
        assertTrue(beforeGenericHelper.contains("live != null -> executeStandbyOnSession"))
        assertTrue(beforeGenericHelper.contains("live != null -> ankleAndApplyOnSession"))
        assertFalse(
            operations.between(
                "private suspend fun executeStandbyOnSession(",
                "private suspend fun withTemporaryStandbySession("
            ).contains("BleInterOperationCooldown")
        )
    }

    @Test fun quietPeriodIsIndependentFromFailureRetryState() {
        listOf(
            "BleRetryPolicy.RETRY_DELAY_MS",
            "LiveRetryCountdown",
            "BatteryRepo.retrySecondsRemaining",
            "BatteryRepo.standbyRetrySecondsRemaining",
            "Retrying"
        ).forEach { forbidden ->
            assertFalse("cooldown references $forbidden", cooldown.contains(forbidden))
        }

        val retryPolicy = source("app/src/main/java/com/onomic/footpilot/BleRetryPolicy.kt")
        assertTrue(retryPolicy.contains("RETRY_DELAY_MS = 15_000L"))
        assertTrue(retryPolicy.contains("ONE_SHOT_CONTROL_RETRIES = 1"))
    }

    private fun assertBefore(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("Missing '$first'", firstIndex >= 0)
        assertTrue("Missing '$second'", secondIndex >= 0)
        assertTrue("Expected '$first' before '$second'", firstIndex < secondIndex)
    }

    private fun String.between(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    private fun String.count(value: String): Int = split(value).size - 1

    private fun source(path: String): String {
        val candidates = listOf(File(path), File("../$path"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not locate $path from ${File(".").absolutePath}"
        }.readText()
    }
}
