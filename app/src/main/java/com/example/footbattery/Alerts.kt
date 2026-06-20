package com.example.footbattery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Centralized notifications so the live connection, the worker, and on-demand checks all match. */
object Alerts {
    const val ONGOING_CH = "monitor"
    const val ALERT_CH = "alert"
    const val ONGOING_ID = 1
    const val ALERT_ID = 2
    const val POLL_STATUS_ID = 3
    const val ACTION_CHECK_NOW = "com.example.footbattery.CHECK_NOW"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CH, "Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        val alert = NotificationChannel(ALERT_CH, "Low battery alerts", NotificationManager.IMPORTANCE_HIGH)
        alert.enableVibration(true)
        nm.createNotificationChannel(alert)
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, 0, i, flags)
    }

    /** Persistent "now monitoring" notification, shown while a live connection is held. */
    fun postOngoing(ctx: Context, pct: Int?, statusText: String) {
        // Short, number-first title so the % is never the part truncated when collapsed.
        val title = if (pct != null) "Battery $pct%" else "Battery —"
        val n = NotificationCompat.Builder(ctx, ONGOING_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, n)
    }

    fun cancelOngoing(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ONGOING_ID)
    }

    /** Human-friendly relative time, e.g. "just now", "5 min ago", "2 hr ago". */
    fun relativeTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "never"
        val secs = (System.currentTimeMillis() - epochMillis) / 1000
        return when {
            secs < 45 -> "just now"
            secs < 90 -> "1 min ago"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 5400 -> "1 hr ago"
            secs < 86400 -> "${secs / 3600} hr ago"
            secs < 172800 -> "1 day ago"
            else -> "${secs / 86400} days ago"
        }
    }

    /** Fixed clock time of a check, e.g. "10:08 PM" (or with date if not today). */
    fun clockTime(ctx: Context, epochMillis: Long): String {
        if (epochMillis <= 0L) return "never"
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val timeFmt = android.text.format.DateFormat.getTimeFormat(ctx)
        val time = timeFmt.format(java.util.Date(epochMillis))
        return if (sameDay) time else {
            val dateFmt = android.text.format.DateFormat.getDateFormat(ctx)
            dateFmt.format(java.util.Date(epochMillis)) + " " + time
        }
    }

    private fun checkNowAction(ctx: Context): NotificationCompat.Action {
        // Start a brief foreground service (normal priority) so the check runs fast and
        // reliably in the background, without opening the app.
        val i = Intent(ctx, CheckNowService::class.java).setAction(ACTION_CHECK_NOW)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(ctx, 2, i, flags)
        else
            PendingIntent.getService(ctx, 2, i, flags)
        return NotificationCompat.Action(0, "Check now", pi)
    }

    /**
     * Persistent status notification shown while background polling is on (and live
     * monitoring is off): battery level + "Last checked: <relative>", a Check now button,
     * and tap-to-open. Call updatePollStatus() to refresh after a reading; call
     * cancelPollStatus() when polling is turned off.
     */
    fun updatePollStatus(ctx: Context) {
        val pct = BatteryRepo.level.value
        // Short, number-first title so the % is never the part truncated when collapsed.
        val title = if (pct != null) "Battery $pct%" else "Battery —"
        val whenText = "Last checked: " + clockTime(ctx, Prefs.lastChecked(ctx))
        val n = NotificationCompat.Builder(ctx, ONGOING_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(whenText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(checkNowAction(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(POLL_STATUS_ID, n)
    }

    fun cancelPollStatus(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(POLL_STATUS_ID)
    }

    /** Transient body swap while a notification-triggered check is running. */
    fun setPollStatusChecking(ctx: Context) = postPollStatusBody(ctx, "Checking…")

    /** Transient body swap when a notification-triggered check fails. */
    fun setPollStatusFailed(ctx: Context) = postPollStatusBody(ctx, "Check failed — foot not in range")

    private fun postPollStatusBody(ctx: Context, body: String) {
        val pct = BatteryRepo.level.value
        val title = if (pct != null) "Battery $pct%" else "Battery —"
        val n = NotificationCompat.Builder(ctx, ONGOING_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(checkNowAction(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(POLL_STATUS_ID, n)
    }

    /** Fire a low-battery alert if below threshold and armed; re-arm once recovered. */
    fun maybeAlert(ctx: Context, pct: Int) {
        val threshold = Prefs.threshold(ctx)
        if (pct < threshold && Prefs.armed(ctx)) {
            showLow(ctx, pct)
            Prefs.setArmed(ctx, false)
        } else if (pct >= threshold) {
            Prefs.setArmed(ctx, true)
        }
    }

    /**
     * Single entry point for any fresh reading (live, background poll, or manual check):
     * stamps the time, runs the low alert, and refreshes the poll-status notification
     * when background polling is on.
     */
    fun recordReading(ctx: Context, pct: Int) {
        Prefs.setLastChecked(ctx, System.currentTimeMillis())
        maybeAlert(ctx, pct)
        if (Prefs.polling(ctx) && !BatteryRepo.running.value) {
            updatePollStatus(ctx)
        }
    }

    private fun showLow(ctx: Context, pct: Int) {
        val n = NotificationCompat.Builder(ctx, ALERT_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("Low battery: $pct%")
            .setContentText("Foot is low — time to charge.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }
}
