package com.onomic.footpilot

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

fun shouldRunScheduledCheck(
    pollingEnabled: Boolean,
    selectedFoot: SelectedFoot?,
    temporarySessionAvailable: Boolean
): Boolean = pollingEnabled && selectedFoot != null && temporarySessionAvailable

/**
 * Periodic full-snapshot check. It skips if live monitoring owns the link or any user
 * operation already owns/reserved the process-wide BLE coordinator.
 */
class BatteryReadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        SelectedFootRepository.ensureInitialized(ctx)
        BatteryRepo.ensureInitialized(ctx)

        // Polling turned off, or live monitoring is active -> nothing to do this cycle.
        if (!shouldRunScheduledCheck(
                pollingEnabled = Prefs.polling(ctx),
                selectedFoot = SelectedFootRepository.current(ctx),
                temporarySessionAvailable = LiveConnection.canUseTemporarySession()
            )
        ) return Result.success()

        Alerts.ensureChannels(ctx)
        FootOperations.scheduledCheck(ctx)
        return Result.success()
    }
}
