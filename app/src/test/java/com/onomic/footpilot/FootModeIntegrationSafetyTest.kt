package com.onomic.footpilot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootModeIntegrationSafetyTest {
    @Test fun modeTransactionContainsNoBatteryAnkleOrStandbyWork() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootModeTransaction.kt") +
            source("app/src/main/java/com/onomic/footpilot/FootModeRefresh.kt")

        assertFalse(source.contains("readBattery"))
        assertFalse(source.contains("AnkleProtocol"))
        assertFalse(source.contains("StandbyProtocol"))
    }

    @Test fun checkAndScheduledPathsContainNoModeQueries() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val checkPath = source.substring(
            source.indexOf("suspend fun checkNow"),
            source.indexOf("suspend fun changeStandby")
        )

        assertFalse(checkPath.contains("queryFootMode"))
        assertFalse(checkPath.contains("FootModeRefresh"))
    }

    @Test fun settingsEntryStartsOneTargetedModesRefresh() {
        val source = source("app/src/main/java/com/onomic/footpilot/MainActivity.kt")
        val settingsEntry = source.substringAfter("onSettings = {").substringBefore("}")

        assertTrue(settingsEntry.contains("showSettings = true"))
        assertTrue(settingsEntry.contains("launchFootModesRefresh"))
    }

    @Test fun temporaryModeSessionUsesExistingReleaseBarrier() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val helper = source.substringAfter("private suspend fun <T> withTemporaryFootModeSession(")
            .substringBefore("private fun modeMutationPrerequisiteError")

        assertTrue(helper.contains("FootGattSession(ctx, target)"))
        assertTrue(helper.contains("BleInterOperationCooldown.awaitReady(target.address)"))
        assertTrue(
            helper.indexOf("BleInterOperationCooldown.awaitReady(target.address)") <
                helper.indexOf("FootGattSession(ctx, target)")
        )
        assertTrue(helper.contains("BleTargetReleaseBarrier.releaseTemporarySession"))
        assertTrue(helper.contains("withContext(NonCancellable)"))
    }

    @Test fun everyRefreshAttemptResolvesReadyOrTemporarySessionFresh() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val defer = source(
            "app/src/main/java/com/onomic/footpilot/FootModeRefreshCoordinatorDefer.kt"
        )
        val refresh = source.substringAfter("private suspend fun refreshFootModes(")
            .substringBefore("private suspend fun runFootModeIntent(")
        val attempt = refresh.substringAfter("private suspend fun performFootModeRefreshAttempt(")
            .substringBefore("private suspend fun refreshFootModesOnSession(")

        assertTrue(refresh.contains("FootModeRefreshOneShotRetry"))
        assertTrue(refresh.contains("attempt = {"))
        assertTrue(refresh.contains("performFootModeRefreshAttempt(ctx, target)"))
        assertTrue(attempt.contains("FootModeRefreshCoordinatorDefer().run"))
        assertTrue(defer.contains("BleOperationCoordinator.tryRun"))
        assertTrue(attempt.contains("LiveConnection.readySession()"))
        assertTrue(attempt.contains("!LiveConnection.canUseTemporarySession()"))
        assertTrue(attempt.contains("withTemporaryFootModeSession(ctx, target)"))
        assertTrue(attempt.indexOf("LiveConnection.readySession()") <
            attempt.indexOf("withTemporaryFootModeSession(ctx, target)"))
        assertFalse(refresh.contains("readBattery"))
        assertFalse(refresh.contains("queryAnkle"))
        assertFalse(refresh.contains("changeStandby"))
    }

    @Test fun coordinatorBusyUsesStateFlowDeferWithoutQueueRetryOrPresentationSideEffects() {
        val operations = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val defer = source(
            "app/src/main/java/com/onomic/footpilot/FootModeRefreshCoordinatorDefer.kt"
        )
        val refresh = operations.substringAfter("private suspend fun refreshFootModes(")
            .substringBefore("private suspend fun runFootModeIntent(")

        assertTrue(defer.contains("BleOperationCoordinator.tryRun"))
        assertTrue(defer.contains("BleOperationCoordinator.state.first { !it.isBusy }"))
        assertTrue(defer.contains("CoordinatedResult.Busy"))
        assertFalse(defer.contains("runQueued"))
        assertFalse(defer.contains("delay("))
        assertFalse(defer.contains("LiveRetryCountdown"))
        assertFalse(defer.contains("BleRetryPolicy"))
        assertFalse(defer.contains("FootModeRepo"))
        assertFalse(refresh.contains("Another foot action is in progress"))
    }

    @Test fun modeRetriesHaveNoIndependentDelayLiteral() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootModeRetry.kt") +
            source("app/src/main/java/com/onomic/footpilot/FootModeRefreshRetry.kt") +
            source("app/src/main/java/com/onomic/footpilot/FootModeRefreshCoordinatorDefer.kt")

        assertTrue(source.contains("LiveRetryCountdown"))
        assertFalse(source.contains("15_000"))
        assertFalse(source.contains("15000"))
        assertFalse(source.contains("10_000"))
        assertFalse(source.contains("10000"))
    }

    @Test fun refreshRetryRemainsQueryOnlyAndCannotSendModeSetCommands() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val refresh = source.substringAfter("private suspend fun refreshFootModes(")
            .substringBefore("private suspend fun runFootModeIntent(")

        assertTrue(refresh.contains("session.queryFootMode(mode)"))
        assertFalse(refresh.contains("session.changeFootMode"))
        assertFalse(refresh.contains("FootModeProtocol.setCommand"))
        assertFalse(refresh.contains("FootModeResponseKind.SET"))
    }

    @Test fun temporaryRefreshRetryCannotBypassInterOperationCooldown() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val attempt = source.substringAfter("private suspend fun performFootModeRefreshAttempt(")
            .substringBefore("private suspend fun refreshFootModesOnSession(")
        val helper = source.substringAfter("private suspend fun <T> withTemporaryFootModeSession(")
            .substringBefore("private fun modeMutationPrerequisiteError")

        assertTrue(attempt.contains("withTemporaryFootModeSession(ctx, target)"))
        assertTrue(helper.contains("BleInterOperationCooldown.awaitReady(target.address)"))
        assertTrue(
            helper.indexOf("BleInterOperationCooldown.awaitReady(target.address)") <
                helper.indexOf("FootGattSession(ctx, target)")
        )
    }

    @Test fun nonReadyPersistentSessionIsTransientWithoutOpeningTemporaryGatt() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val mutation = source.substringAfter("private suspend fun performFootModeAttempt(")
            .substringBefore("private fun footModeOperationKind(")

        assertTrue(mutation.contains("modeMutationPrerequisiteError(ctx, target)"))
        assertTrue(mutation.contains("!LiveConnection.canUseTemporarySession()"))
        assertTrue(mutation.contains("FootModeMutationAttemptResult.TransientFailure"))
    }

    @Test fun footModeSwitchUsesGreenAccentAndNeverStandbyWarningColor() {
        val source = source("app/src/main/java/com/onomic/footpilot/MainActivity.kt")
        val row = source.substringAfter("private fun FootModeRow(")
            .substringBefore("private fun StepButton")

        assertTrue(row.contains("checkedTrackColor = accent"))
        assertFalse(row.contains("Warn"))
    }

    @Test fun notificationActionsRemainCheckStandbyAndAutoOnly() {
        val source = source("app/src/main/java/com/onomic/footpilot/Alerts.kt")

        assertFalse(source.contains("CHAIR_EXIT"))
        assertFalse(source.contains("RELAX_ON"))
        assertFalse(source.contains("RELAX_OFF"))
    }

    private fun source(path: String): String {
        val candidates = listOf(File(path), File("../$path"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not locate $path from ${File(".").absolutePath}"
        }.readText()
    }
}
