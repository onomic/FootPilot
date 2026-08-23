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
        val helper = source.substringAfter("withTemporaryFootModeSession(")
            .substringBefore("private fun modeExecutionPrerequisiteError")

        assertTrue(helper.contains("FootGattSession(ctx, target)"))
        assertTrue(helper.contains("BleInterOperationCooldown.awaitReady(target.address)"))
        assertTrue(
            helper.indexOf("BleInterOperationCooldown.awaitReady(target.address)") <
                helper.indexOf("FootGattSession(ctx, target)")
        )
        assertTrue(helper.contains("BleTargetReleaseBarrier.releaseTemporarySession"))
        assertTrue(helper.contains("withContext(NonCancellable)"))
    }

    @Test fun refreshReusesReadySessionOrOneSafeTemporarySessionWithoutRetry() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootOperations.kt")
        val refresh = source.substringAfter("private suspend fun refreshFootModes(")
            .substringBefore("private suspend fun runFootModeIntent(")

        assertTrue(refresh.contains("LiveConnection.readySession()"))
        assertTrue(refresh.contains("!LiveConnection.canUseTemporarySession()"))
        assertTrue(refresh.contains("withTemporaryFootModeSession(ctx, target)"))
        assertTrue(refresh.contains("refreshFootModesOnSession(target, session)"))
        assertFalse(refresh.contains("FootModeOneShotRetry"))
        assertFalse(refresh.contains("readBattery"))
        assertFalse(refresh.contains("queryAnkle"))
        assertFalse(refresh.contains("changeStandby"))
    }

    @Test fun modeRetryHasNoIndependentDelayLiteral() {
        val source = source("app/src/main/java/com/onomic/footpilot/FootModeRetry.kt")

        assertTrue(source.contains("LiveRetryCountdown"))
        assertFalse(source.contains("15_000"))
        assertFalse(source.contains("15000"))
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
