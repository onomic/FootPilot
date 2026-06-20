package com.example.footbattery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic background check. Each run briefly connects, reads, alerts if low, disconnects.
 * Skips if live monitoring already holds the connection (the foot allows only one).
 */
class BatteryReadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // Polling turned off, or live monitoring is active -> nothing to do this cycle.
        if (!Prefs.polling(ctx)) return Result.success()
        if (BatteryRepo.running.value) return Result.success()

        Alerts.ensureChannels(ctx)
        val pct = BleReader.readOnce(ctx)
        if (pct != null) {
            BatteryRepo.level.value = pct
            BatteryRepo.status.value = "Checked (background)"
            Alerts.recordReading(ctx, pct)
        }
        return Result.success()
    }
}
