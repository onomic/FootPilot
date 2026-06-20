package com.example.footbattery

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brief foreground service that performs an on-demand battery check from the notification's
 * "Check now" button — WITHOUT opening the app. A foreground service runs at normal priority
 * (unlike a BroadcastReceiver + goAsync, which is throttled and gets starved), so the read
 * is as fast and reliable as the in-app check. It updates the poll-status notification in
 * place, then stops itself.
 */
class CheckNowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctx = applicationContext
        Alerts.ensureChannels(ctx)

        // We must post a foreground notification immediately. Reuse the poll-status look,
        // switched to "Checking…", as the required foreground notification.
        startForeground(Alerts.POLL_STATUS_ID, buildChecking(ctx))

        // If live monitoring already holds the link, just refresh and stop.
        if (BatteryRepo.running.value) {
            LiveConnection.refresh()
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (BleReader.isBusy()) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        scope.launch {
            val pct = BleReader.readOnce(ctx)
            if (pct != null) {
                BatteryRepo.level.value = pct
                Alerts.recordReading(ctx, pct)   // stamps time + refreshes poll notification
            } else {
                Alerts.setPollStatusFailed(ctx)
            }
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    private fun buildChecking(ctx: Context): Notification {
        val pct = BatteryRepo.level.value
        val title = if (pct != null) "Battery $pct%" else "Battery —"
        return NotificationCompat.Builder(ctx, Alerts.ONGOING_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText("Checking…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun stopSelfSafely() {
        // Detach the foreground notification but DON'T remove it — recordReading/ setPollStatusFailed
        // has already re-posted the updated poll-status notification under the same id.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
