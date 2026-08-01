package com.example.footbattery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic full-snapshot check. It skips if live monitoring owns the link or any user
 * operation already owns/reserved the process-wide BLE coordinator.
 */
class BatteryReadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        BatteryRepo.ensureInitialized(ctx)

        // Polling turned off, or live monitoring is active -> nothing to do this cycle.
        if (!Prefs.polling(ctx)) return Result.success()
        if (!LiveConnection.canUseTemporarySession()) return Result.success()

        Alerts.ensureChannels(ctx)
        FootOperations.scheduledCheck(ctx)
        return Result.success()
    }
}
