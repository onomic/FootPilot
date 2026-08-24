package com.onomic.footpilot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyIntegrationSafetyTest {
    private val operations by lazy {
        source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
    }

    @Test fun publicAbsoluteChangeKeepsUnknownFailClosedButInternalRunnerDoesNotRecheckSnapshot() {
        val publicEntry = operations.between(
            "suspend fun changeStandby",
            "/** Notification Standby action"
        )
        val publicGate = operations.between(
            "private suspend fun executeStandbyRequest(",
            "private suspend fun runStandbyIntent("
        )
        val internalAttempt = operations.between(
            "private suspend fun performStandbyAttempt(",
            "private suspend fun executeStandbyOnSession("
        )

        assertTrue(publicEntry.contains("requireKnownPublicState = true"))
        assertTrue(publicGate.contains("BatteryRepo.snapshot.value.standby == StandbyState.UNKNOWN"))
        assertFalse(internalAttempt.contains("BatteryRepo.snapshot"))
        assertFalse(internalAttempt.contains("changeStandby(ctx"))
        assertFalse(internalAttempt.contains("toggleStandby(ctx"))
    }

    @Test fun notificationToggleUsesTheSameRunnerWithoutRequiringRenderedStandbyTruth() {
        val toggle = operations.between(
            "suspend fun toggleStandby",
            "private fun beginStandbyRequest("
        )

        assertTrue(toggle.contains("StandbyAttemptRequest.Toggle"))
        assertTrue(toggle.contains("requireKnownPublicState = false"))
        assertFalse(toggle.contains("BatteryRepo.snapshot.value.standby"))
    }

    @Test fun everyRetryAttemptUsesCoordinatorThenReadyOrSafeTemporarySession() {
        val attempt = operations.between(
            "private suspend fun performStandbyAttempt(",
            "private suspend fun executeStandbyOnSession("
        )

        assertTrue(attempt.contains("BleOperationCoordinator.runDeviceControl(kind)"))
        assertTrue(attempt.contains("LiveConnection.readySession()"))
        assertTrue(attempt.contains("!LiveConnection.canUseTemporarySession()"))
        assertTrue(attempt.contains("withTemporaryStandbySession(ctx, target, token, request)"))
    }

    @Test fun temporaryStandbyAttemptRetainsTheExistingReleaseBarrier() {
        val temporary = operations.between(
            "private suspend fun withTemporaryStandbySession(",
            "private fun applyStandbyAttempt("
        )

        assertTrue(temporary.contains("FootGattSession(ctx, target)"))
        assertTrue(temporary.contains("BleInterOperationCooldown.awaitReady(target.address)"))
        assertTrue(
            temporary.indexOf("BleInterOperationCooldown.awaitReady(target.address)") <
                temporary.indexOf("FootGattSession(ctx, target)")
        )
        assertTrue(temporary.contains("BleTargetReleaseBarrier.releaseTemporarySession"))
        assertTrue(temporary.contains("withContext(NonCancellable)"))
    }

    @Test fun queryOnlySessionApiUsesTheExistingSerializedAnkleQueryPrimitive() {
        val session = source("app/src/main/java/com/onomic/footpilot/FootGattSession.kt")
        val query = session.between(
            "suspend fun queryAnkleAngle()",
            "suspend fun changeAnkle("
        )

        assertTrue(query.contains("transactionMutex.withLock"))
        assertTrue(query.contains("ensureUsable()"))
        assertTrue(query.contains("exchangeAnkle("))
        assertTrue(query.contains("AnkleProtocol.queryCommand()"))
        assertTrue(query.contains("AnkleResponseKind.QUERY"))
        assertFalse(query.contains("AnkleProtocol.setCommand"))
        assertFalse(query.contains("readBattery"))
        assertFalse(query.contains("StandbyProtocol"))
    }

    @Test fun absoluteAndNotificationStandbyOffShareOneSameSessionRecoveryPath() {
        val execution = operations.between(
            "private suspend fun executeStandbyOnSession(",
            "private suspend fun withTemporaryStandbySession("
        )

        assertTrue(execution.contains("session.changeStandby(request.requested)"))
        assertTrue(execution.contains("session.toggleStandby()"))
        assertTrue(execution.count("recoverAnkleAfterVerifiedStandbyOff(") == 1)
        assertTrue(execution.contains("queryAnkle = session::queryAnkleAngle"))
        assertTrue(execution.contains("AnkleRepo.confirm(ctx, recovery.millidegrees, message = null)"))
        assertTrue(execution.contains("AnkleRepo.fail(STANDBY_OFF_ANKLE_UNCONFIRMED)"))
        assertTrue(
            execution.indexOf("session.toggleStandby()") <
                execution.indexOf("recoverAnkleAfterVerifiedStandbyOff(")
        )
        assertFalse(execution.contains("FootGattSession(ctx"))
        assertFalse(execution.contains("BleInterOperationCooldown"))
        assertFalse(execution.contains("readFullSnapshot"))
        assertFalse(execution.contains("BleOperationCoordinator"))
        assertFalse(execution.contains("AnkleProtocol.setCommand"))
    }

    @Test fun recoveryGateRequiresVerifiedOffAndAnUnconfirmedAnkle() {
        val recovery = operations.between(
            "internal suspend fun recoverAnkleAfterVerifiedStandbyOff(",
            "/** Classifies snapshot completeness"
        )

        assertTrue(recovery.contains("!read.verified"))
        assertTrue(recovery.contains("read.finalState != StandbyState.OFF"))
        assertTrue(recovery.contains("confirmedMd != null"))
        assertTrue(recovery.contains("AnkleProtocol.isSupported(response.millidegrees)"))
        assertTrue(recovery.count("queryAnkle()") == 1)
    }

    @Test fun checkNowStillUsesItsExistingFullSnapshotRecovery() {
        val check = operations.between(
            "internal suspend fun readAndApplyOnSession(",
            "private fun applyStandbyRead("
        )

        assertTrue(check.contains("session.readFullSnapshot()"))
        assertTrue(check.contains("AnkleRepo.applySnapshotRead(ctx, read)"))
    }

    @Test fun countdownPublishesOnlyProcessStateAndNeverRefreshesNotificationEachSecond() {
        val runner = operations.between(
            "private suspend fun runStandbyIntent(",
            "private suspend fun performStandbyAttempt("
        )
        val publish = runner.between(
            "publishSecondsRemaining =",
            "onRetryScheduled ="
        )

        assertTrue(publish.contains("BatteryRepo.standbyRetrySecondsRemaining.value"))
        assertFalse(publish.contains("Alerts."))
        assertTrue(runner.count("Alerts.showOperation(ctx, \"Retrying standby...\")") == 1)
    }

    @Test fun selectedFootResetInvalidatesPendingStandbyGeneration() {
        val selectedFoot = source("app/src/main/java/com/onomic/footpilot/SelectedFoot.kt")
        val reset = selectedFoot.substringAfter("private fun resetInMemoryDeviceState")

        assertTrue(reset.contains("FootOperations.cancelPendingStandbyOperations()"))
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
