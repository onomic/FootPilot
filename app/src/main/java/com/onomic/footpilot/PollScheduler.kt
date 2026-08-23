package com.onomic.footpilot

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object PollScheduler {
    private const val NAME = "battery_poll"

    /** Enable/disable periodic polling, or update its interval. */
    fun apply(ctx: Context, enabled: Boolean, intervalMinutes: Int) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        // WorkManager enforces a 15-minute minimum period.
        val minutes = intervalMinutes.coerceAtLeast(15).toLong()
        val req = PeriodicWorkRequestBuilder<BatteryReadWorker>(minutes, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
