package com.onomic.footpilot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRetryContractTest {
    @Test fun manualNotificationAndScheduledChecksRemainSingleHighLevelAttempts() {
        val operations = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val checkPath = operations.between(
            "suspend fun checkNow",
            "suspend fun scheduledCheck"
        )
        val scheduledPath = operations.between(
            "suspend fun scheduledCheck",
            "suspend fun changeStandby"
        )
        val service = source("app/src/main/java/com/onomic/footpilot/CheckNowService.kt")

        assertFalse(checkPath.contains("RetryCountdown"))
        assertFalse(checkPath.contains("OneShotRetry"))
        assertFalse(checkPath.contains("awaitRetry"))
        assertFalse(checkPath.contains("standbyRetrySecondsRemaining"))
        assertTrue(scheduledPath.contains("return checkNow(app, CheckOrigin.SCHEDULED)"))
        assertTrue(
            service.count("FootOperations.checkNow(ctx, CheckOrigin.NOTIFICATION)") == 1
        )
    }

    @Test fun workerAlwaysCompletesItsScheduledCycleWithoutWorkManagerRetry() {
        val worker = source("app/src/main/java/com/onomic/footpilot/BatteryReadWorker.kt")

        assertTrue(worker.count("FootOperations.scheduledCheck(ctx)") == 1)
        assertTrue(worker.contains("return Result.success()"))
        assertFalse(worker.contains("Result.retry()"))
    }

    @Test fun footModesRefreshRemainsOneShot() {
        val operations = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val refresh = operations.between(
            "private suspend fun refreshFootModes(",
            "private suspend fun runFootModeIntent("
        )

        assertFalse(refresh.contains("OneShotRetry"))
        assertFalse(refresh.contains("awaitRetry"))
        assertTrue(refresh.count("refreshFootModesOnSession(target") == 2)
    }

    @Test fun finePresetAndAutoPathsContainNoAutomaticCommandRunner() {
        val operations = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val publicMovement = operations.between(
            "suspend fun adjustFine(",
            "private suspend fun refreshFootModes("
        )
        val movementExecution = operations.between(
            "private suspend fun runAnkleRequest(",
            "internal suspend fun readAndApplyOnSession("
        ) + operations.between(
            "private suspend fun ankleAndApplyOnSession(",
            "private suspend fun withTemporarySession("
        )

        listOf(publicMovement, movementExecution).forEach { path ->
            assertFalse(path.contains("OneShotRetry"))
            assertFalse(path.contains("awaitRetry"))
            assertFalse(path.contains("standbyRetrySecondsRemaining"))
        }
    }

    @Test fun retryDelayDoesNotLeakIntoGattCommandOrReleaseTimeouts() {
        val independentTimeoutFiles = listOf(
            "app/src/main/java/com/onomic/footpilot/FootGattSession.kt",
            "app/src/main/java/com/onomic/footpilot/BleTargetReleaseBarrier.kt",
            "app/src/main/java/com/onomic/footpilot/TargetRelease.kt",
            "app/src/main/java/com/onomic/footpilot/AutoAlignmentTransaction.kt"
        )

        independentTimeoutFiles.forEach { path ->
            assertFalse(source(path).contains("BleRetryPolicy"))
        }
    }

    @Test fun retryParticipantsHaveNoIndependentFifteenSecondDelayLiteral() {
        val retryParticipants = listOf(
            "app/src/main/java/com/onomic/footpilot/LiveRetry.kt",
            "app/src/main/java/com/onomic/footpilot/LiveConnection.kt",
            "app/src/main/java/com/onomic/footpilot/FootModeRetry.kt",
            "app/src/main/java/com/onomic/footpilot/StandbyRetry.kt",
            "app/src/main/java/com/onomic/footpilot/FootOperations.kt"
        )

        retryParticipants.forEach { path ->
            val text = source(path)
            assertFalse("independent retry delay in $path", text.contains("15_000"))
            assertFalse("independent retry delay in $path", text.contains("15000"))
        }
        assertTrue(
            source("app/src/main/java/com/onomic/footpilot/BleRetryPolicy.kt")
                .contains("RETRY_DELAY_MS = 15_000L")
        )
    }

    @Test fun interOperationCooldownDoesNotPublishOrReuseRetryState() {
        val cooldown = source(
            "app/src/main/java/com/onomic/footpilot/BleInterOperationCooldown.kt"
        )

        assertFalse(cooldown.contains("BleRetryPolicy"))
        assertFalse(cooldown.contains("LiveRetryCountdown"))
        assertFalse(cooldown.contains("retrySecondsRemaining"))
        assertFalse(cooldown.contains("standbyRetrySecondsRemaining"))
        assertFalse(cooldown.contains("Retrying"))
    }

    @Test fun standbyOffAnkleRecoveryCannotTurnAConfirmedStandbyIntoRetry() {
        val operations = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val recovery = operations.between(
            "internal suspend fun recoverAnkleAfterVerifiedStandbyOff(",
            "/** Classifies snapshot completeness"
        )
        val execution = operations.between(
            "private suspend fun executeStandbyOnSession(",
            "private suspend fun withTemporaryStandbySession("
        )

        assertFalse(recovery.contains("StandbyOneShotRetry"))
        assertFalse(recovery.contains("StandbyAttemptResult.TransientFailure"))
        assertFalse(recovery.contains("LiveRetryCountdown"))
        assertTrue(execution.contains("return StandbyAttemptResult.Transaction(read)"))
        assertFalse(execution.contains("StandbyAttemptResult.TransientFailure"))
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
