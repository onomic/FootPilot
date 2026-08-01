package com.example.footbattery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** Centralized verified-snapshot notifications and low-battery alerts. */
object Alerts {
    const val ONGOING_CH = "monitor"
    const val ALERT_CH = "alert"
    const val ONGOING_ID = 1
    const val ALERT_ID = 2
    const val POLL_STATUS_ID = 3

    const val ACTION_CHECK_NOW = "com.example.footbattery.CHECK_NOW"
    const val ACTION_STANDBY_ON = "com.example.footbattery.STANDBY_ON"
    const val ACTION_STANDBY_OFF = "com.example.footbattery.STANDBY_OFF"

    private const val REQUEST_OPEN_APP = 10
    private const val REQUEST_CHECK_NOW = 20
    private const val REQUEST_STANDBY_ON = 21
    private const val REQUEST_STANDBY_OFF = 22
    private const val TRANSIENT_STATUS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    private val transientGeneration = AtomicInteger(0)

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CH, "Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        val alert = NotificationChannel(ALERT_CH, "Low battery alerts", NotificationManager.IMPORTANCE_HIGH)
        alert.enableVibration(true)
        nm.createNotificationChannel(alert)
    }

    /** Persistent notification for a live connection. */
    fun postOngoing(ctx: Context, statusText: String? = null) {
        BatteryRepo.ensureInitialized(ctx)
        val notification = buildStateNotification(
            ctx = ctx,
            ongoing = true,
            statusText = statusText,
            includeActions = statusText == null && actionsAreSafe(ctx),
            liveBatteryLevel = BatteryRepo.level.value
        )
        ctx.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, notification)
    }

    fun cancelOngoing(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ONGOING_ID)
    }

    /** Persistent status notification while polling is enabled and live monitoring is off. */
    fun updatePollStatus(ctx: Context) {
        BatteryRepo.ensureInitialized(ctx)
        val notification = buildStateNotification(
            ctx = ctx,
            ongoing = true,
            statusText = null,
            includeActions = actionsAreSafe(ctx),
            liveBatteryLevel = null
        )
        ctx.getSystemService(NotificationManager::class.java).notify(POLL_STATUS_ID, notification)
    }

    fun cancelPollStatus(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(POLL_STATUS_ID)
    }

    /** Immediately replaces any applicable persistent notification and suppresses actions. */
    fun showOperation(ctx: Context, text: String) {
        transientGeneration.incrementAndGet()
        postApplicable(ctx, text, includeActions = false)
    }

    /** Restore verified state/actions, optionally showing a temporary human-readable result. */
    fun refreshApplicable(ctx: Context, transientStatus: String? = null) {
        val generation = transientGeneration.incrementAndGet()
        postApplicable(ctx, transientStatus, includeActions = actionsAreSafe(ctx))
        if (transientStatus != null) {
            handler.postDelayed({
                if (transientGeneration.get() == generation && !BleOperationCoordinator.isBusy()) {
                    postApplicable(ctx.applicationContext, null, includeActions = actionsAreSafe(ctx))
                }
            }, TRANSIENT_STATUS_MS)
        }
    }

    /** Foreground-service notification shown immediately for a notification action. */
    fun operationNotification(ctx: Context, text: String): Notification =
        buildStateNotification(
            ctx = ctx,
            ongoing = true,
            statusText = text,
            includeActions = false,
            liveBatteryLevel = BatteryRepo.level.value.takeIf {
                LiveConnection.isMonitoringRequested()
            }
        )

    /** Refreshes only the live-monitoring notification for a battery notification. */
    fun refreshLiveBattery(ctx: Context) {
        if (!LiveConnection.isMonitoringRequested()) return
        val statusText = BleOperationCoordinator.state.value.visibleOperation?.statusText
        val notification = buildStateNotification(
            ctx = ctx,
            ongoing = true,
            statusText = statusText,
            includeActions = statusText == null && actionsAreSafe(ctx),
            liveBatteryLevel = BatteryRepo.level.value
        )
        ctx.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, notification)
    }

    private fun postApplicable(ctx: Context, statusText: String?, includeActions: Boolean) {
        when {
            LiveConnection.isMonitoringRequested() -> {
                val notification = buildStateNotification(
                    ctx,
                    true,
                    statusText,
                    includeActions,
                    BatteryRepo.level.value
                )
                ctx.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, notification)
            }
            Prefs.polling(ctx) -> {
                val notification = buildStateNotification(
                    ctx,
                    true,
                    statusText,
                    includeActions,
                    liveBatteryLevel = null
                )
                ctx.getSystemService(NotificationManager::class.java).notify(POLL_STATUS_ID, notification)
            }
        }
    }

    private fun buildStateNotification(
        ctx: Context,
        ongoing: Boolean,
        statusText: String?,
        includeActions: Boolean,
        liveBatteryLevel: Int?
    ): Notification {
        val snapshot = BatteryRepo.snapshot.value
        val display = SnapshotPresentation.create(snapshot, liveBatteryLevel)
        val batteryLine = display.batteryLine
        val standbyLine = display.standbyLine
        val checkedLine = display.checkedLine(
            snapshot.lastChecked.takeIf { it > 0L }?.let { clockTime(ctx, it) }
        )
        val collapsed = statusText ?: display.verificationMessage?.let {
            "$standbyLine · $it"
        } ?: "$standbyLine · $checkedLine"

        val style = NotificationCompat.InboxStyle()
            .addLine(batteryLine)
            .addLine(standbyLine)
            .addLine(checkedLine)
        display.verificationMessage?.let { style.addLine(it) }
        if (statusText != null) style.addLine(statusText)

        val builder = NotificationCompat.Builder(ctx, ONGOING_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(batteryLine)
            .setContentText(collapsed)
            .setStyle(style)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))

        if (includeActions) {
            builder.addAction(operationAction(ctx, ACTION_CHECK_NOW, "Check now", REQUEST_CHECK_NOW))
            when (display.standby) {
                StandbyState.ON -> builder.addAction(
                    operationAction(ctx, ACTION_STANDBY_OFF, "Turn standby off", REQUEST_STANDBY_OFF)
                )
                StandbyState.OFF -> builder.addAction(
                    operationAction(ctx, ACTION_STANDBY_ON, "Turn standby on", REQUEST_STANDBY_ON)
                )
                StandbyState.UNKNOWN -> Unit
            }
        }
        return builder.build()
    }

    private fun openApp(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, REQUEST_OPEN_APP, intent, pendingIntentFlags())
    }

    private fun operationAction(
        ctx: Context,
        action: String,
        label: String,
        requestCode: Int
    ): NotificationCompat.Action {
        val intent = Intent(ctx, CheckNowService::class.java).setAction(action)
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(ctx, requestCode, intent, pendingIntentFlags())
        } else {
            PendingIntent.getService(ctx, requestCode, intent, pendingIntentFlags())
        }
        return NotificationCompat.Action(0, label, pending)
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    @SuppressLint("MissingPermission")
    private fun actionsAreSafe(ctx: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val connectionSafe = LiveConnection.isReady() || LiveConnection.canUseTemporarySession()
        val bluetoothEnabled = try {
            permissionGranted &&
                (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
        return bluetoothEnabled && connectionSafe && !BleOperationCoordinator.isBusy()
    }

    /** Fixed clock time of a complete snapshot, with a date when it was not taken today. */
    fun clockTime(ctx: Context, epochMillis: Long): String {
        if (epochMillis <= 0L) return "never"
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(epochMillis))
        return if (sameDay) time else {
            android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date(epochMillis)) + " " + time
        }
    }

    /** Live values can alert without mutating the persisted complete snapshot timestamp. */
    fun maybeAlert(ctx: Context, pct: Int) {
        val threshold = Prefs.threshold(ctx)
        val wasArmed = Prefs.armed(ctx)
        val decision = LowBatteryAlertReducer.reduce(wasArmed, pct, threshold)
        if (decision.shouldAlert) showLow(ctx, pct)
        if (decision.armed != wasArmed) Prefs.setArmed(ctx, decision.armed)
    }

    private fun showLow(ctx: Context, pct: Int) {
        val notification = NotificationCompat.Builder(ctx, ALERT_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("Low battery: $pct%")
            .setContentText("Foot is low — time to charge.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification)
    }
}
