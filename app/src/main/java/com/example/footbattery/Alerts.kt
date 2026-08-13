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
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Centralized verified-snapshot notifications and low-battery alerts. */
object Alerts {
    const val ONGOING_CH = "monitor"
    const val ALERT_CH = "alert"
    const val ONGOING_ID = 1
    const val ALERT_ID = 2
    const val POLL_STATUS_ID = 3

    const val ACTION_CHECK_NOW = "com.example.footbattery.CHECK_NOW"
    const val ACTION_STANDBY = "com.example.footbattery.STANDBY"
    const val ACTION_AUTO = "com.example.footbattery.AUTO_ALIGN"
    const val ACTION_PRESET_BAREFOOT = "com.example.footbattery.PRESET_BAREFOOT"
    const val ACTION_PRESET_RUNNING = "com.example.footbattery.PRESET_RUNNING"
    const val ACTION_PRESET_DRESS = "com.example.footbattery.PRESET_DRESS"
    const val ACTION_PRESET_BOOTS = "com.example.footbattery.PRESET_BOOTS"

    private const val REQUEST_OPEN_APP = 10
    private const val REQUEST_CHECK_NOW = 20
    private const val REQUEST_STANDBY = 23
    private const val REQUEST_AUTO = 24
    private const val REQUEST_PRESET_BAREFOOT = 30
    private const val REQUEST_PRESET_RUNNING = 31
    private const val REQUEST_PRESET_DRESS = 32
    private const val REQUEST_PRESET_BOOTS = 33
    private const val TRANSIENT_STATUS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    private val transientStatus = TransientStatusStore(TRANSIENT_STATUS_MS)

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
        AnkleRepo.ensureInitialized(ctx)
        PresetRepository.ensureInitialized(ctx)
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
        AnkleRepo.ensureInitialized(ctx)
        PresetRepository.ensureInitialized(ctx)
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
        transientStatus.beginOperation()
        postApplicable(ctx, text, includeActions = false)
    }

    /** Restore verified state/actions, optionally showing a temporary human-readable result. */
    fun refreshApplicable(ctx: Context, transientText: String? = null) {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        val token = if (transientText != null) {
            transientStatus.replace(transientText, now)
        } else {
            transientStatus.clear()
            null
        }
        postResolvedApplicable(app, now)
        if (token != null) {
            handler.postDelayed({
                val callbackNow = SystemClock.elapsedRealtime()
                if (transientStatus.expire(token, callbackNow)) {
                    postResolvedApplicable(app, callbackNow)
                }
            }, (token.expiresAtMs - now).coerceAtLeast(0L))
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
        val plan = liveBatteryRefreshPlan(LiveConnection.isMonitoringRequested())
        if (!plan.refreshOngoing) return
        val model = resolvedModel(
            ctx,
            liveBatteryLevel = BatteryRepo.level.value,
            nowMs = SystemClock.elapsedRealtime()
        )
        val notification = buildStateNotification(
            ctx = ctx,
            ongoing = true,
            statusText = model.statusText,
            includeActions = model.includeActions,
            liveBatteryLevel = BatteryRepo.level.value
        )
        ctx.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, notification)
    }

    private fun postResolvedApplicable(ctx: Context, nowMs: Long) {
        val liveBattery = BatteryRepo.level.value.takeIf {
            LiveConnection.isMonitoringRequested()
        }
        val model = resolvedModel(ctx, liveBattery, nowMs)
        postApplicable(ctx, model.statusText, model.includeActions)
    }

    private fun resolvedModel(
        ctx: Context,
        liveBatteryLevel: Int?,
        nowMs: Long
    ): StateNotificationModel = NotificationStatePresentation.create(
        snapshot = BatteryRepo.snapshot.value,
        liveBatteryLevel = liveBatteryLevel,
        activeOperationText = BleOperationCoordinator.state.value.visibleOperation?.statusText,
        transientText = transientStatus.visibleText(nowMs),
        actionsSafe = actionsAreSafe(ctx)
    )

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
        val ankle = AnkleRepo.state.value
        val presets = PresetRepository.state.value
        val content = StateNotificationContentPresentation.create(
            display = display,
            ankle = ankle,
            presets = presets,
            formattedTime = snapshot.lastChecked.takeIf { it > 0L }?.let {
                clockTime(ctx, it)
            },
            statusText = statusText
        )
        val collapsed = RemoteViews(ctx.packageName, R.layout.notification_state_collapsed).apply {
            setTextViewText(R.id.notification_collapsed_battery, content.title)
            setTextViewText(R.id.notification_collapsed_status, content.collapsedText)
        }
        val autoVisible = ankle.operation in setOf(
            AnkleOperation.AUTO_STARTING,
            AnkleOperation.AUTO_RUNNING,
            AnkleOperation.VERIFYING
        ) || statusText == "Automatic alignment" ||
            statusText?.startsWith("Keep foot flat") == true
        val expanded = if (autoVisible) {
            RemoteViews(ctx.packageName, R.layout.notification_auto).apply {
                setTextViewText(R.id.notification_auto_battery, content.title)
                setTextViewText(R.id.notification_auto_title, "Automatic alignment")
                setTextViewText(
                    R.id.notification_auto_instruction,
                    "Keep foot flat until the second beep, then lift your foot."
                )
            }
        } else {
            expandedStateViews(ctx, content, display, ankle, presets, includeActions)
        }

        val builder = NotificationCompat.Builder(ctx, ONGOING_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(content.title)
            .setContentText(content.collapsedText)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))

        stateNotificationActions(display, ankle, includeActions).forEach { action ->
            when (action) {
                StateNotificationAction.CHECK_NOW -> builder.addAction(
                    operationAction(ctx, ACTION_CHECK_NOW, "Check", REQUEST_CHECK_NOW)
                )
                StateNotificationAction.STANDBY -> builder.addAction(
                    operationAction(ctx, ACTION_STANDBY, "Standby", REQUEST_STANDBY)
                )
                StateNotificationAction.AUTO -> builder.addAction(
                    operationAction(ctx, ACTION_AUTO, "Auto", REQUEST_AUTO)
                )
            }
        }
        return builder.build()
    }

    private fun expandedStateViews(
        ctx: Context,
        content: StateNotificationContent,
        display: SnapshotDisplayState,
        ankle: AnkleState,
        presets: PresetState,
        includeActions: Boolean
    ): RemoteViews = RemoteViews(ctx.packageName, R.layout.notification_state_expanded).apply {
        setTextViewText(R.id.notification_expanded_battery, content.title)
        setTextViewText(R.id.notification_expanded_standby, content.standbyText)
        setTextViewText(R.id.notification_angle_text, content.angleSummaryText)
        val summary = content.summaryPreset
        if (summary == null) {
            setViewVisibility(R.id.notification_summary_image, View.GONE)
        } else {
            setViewVisibility(R.id.notification_summary_image, View.VISIBLE)
            setImageViewResource(R.id.notification_summary_image, presetDrawable(summary))
        }
        val operation = content.operationText
        setViewVisibility(
            R.id.notification_operation_status,
            if (operation == null) View.GONE else View.VISIBLE
        )
        operation?.let { setTextViewText(R.id.notification_operation_status, it) }

        val clickable = notificationPresetActions(display, ankle, presets, includeActions)
        FootwearPreset.fixedOrder.forEach { preset ->
            val configured = presets.targets.target(preset) != null
            val active = display.standby == StandbyState.OFF && ankle.confirmedMd != null &&
                presets.targets.target(preset) == ankle.confirmedMd
            val background = when {
                active -> R.drawable.notification_preset_selected
                configured -> R.drawable.notification_preset_unselected
                else -> R.drawable.notification_preset_disabled
            }
            setInt(presetCellId(preset), "setBackgroundResource", background)
            setTextViewText(
                presetLabelId(preset),
                if (active) "${preset.displayName} ✓" else preset.displayName
            )
            setTextColor(
                presetLabelId(preset),
                Color.parseColor(if (active) "#34E0A1" else if (configured) "#F1F5F4" else "#7C8D89")
            )
            setInt(presetImageId(preset), "setImageAlpha", if (configured) 255 else 92)
            if (preset in clickable) {
                setOnClickPendingIntent(presetCellId(preset), presetPendingIntent(ctx, preset))
            }
        }
    }

    private fun presetPendingIntent(ctx: Context, preset: FootwearPreset): PendingIntent {
        val (action, requestCode) = when (preset) {
            FootwearPreset.BAREFOOT -> ACTION_PRESET_BAREFOOT to REQUEST_PRESET_BAREFOOT
            FootwearPreset.RUNNING -> ACTION_PRESET_RUNNING to REQUEST_PRESET_RUNNING
            FootwearPreset.DRESS -> ACTION_PRESET_DRESS to REQUEST_PRESET_DRESS
            FootwearPreset.BOOTS -> ACTION_PRESET_BOOTS to REQUEST_PRESET_BOOTS
        }
        val intent = Intent(ctx, CheckNowService::class.java).setAction(action)
        return PendingIntent.getForegroundService(ctx, requestCode, intent, pendingIntentFlags())
    }

    private fun presetDrawable(preset: FootwearPreset): Int = when (preset) {
        FootwearPreset.BAREFOOT -> R.drawable.preset_barefoot
        FootwearPreset.RUNNING -> R.drawable.preset_running
        FootwearPreset.DRESS -> R.drawable.preset_dress
        FootwearPreset.BOOTS -> R.drawable.preset_boots
    }

    private fun presetCellId(preset: FootwearPreset): Int = when (preset) {
        FootwearPreset.BAREFOOT -> R.id.notification_preset_barefoot
        FootwearPreset.RUNNING -> R.id.notification_preset_running
        FootwearPreset.DRESS -> R.id.notification_preset_dress
        FootwearPreset.BOOTS -> R.id.notification_preset_boots
    }

    private fun presetImageId(preset: FootwearPreset): Int = when (preset) {
        FootwearPreset.BAREFOOT -> R.id.notification_preset_barefoot_image
        FootwearPreset.RUNNING -> R.id.notification_preset_running_image
        FootwearPreset.DRESS -> R.id.notification_preset_dress_image
        FootwearPreset.BOOTS -> R.id.notification_preset_boots_image
    }

    private fun presetLabelId(preset: FootwearPreset): Int = when (preset) {
        FootwearPreset.BAREFOOT -> R.id.notification_preset_barefoot_label
        FootwearPreset.RUNNING -> R.id.notification_preset_running_label
        FootwearPreset.DRESS -> R.id.notification_preset_dress_label
        FootwearPreset.BOOTS -> R.id.notification_preset_boots_label
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
        val pending = PendingIntent.getForegroundService(
            ctx,
            requestCode,
            intent,
            pendingIntentFlags()
        )
        return NotificationCompat.Action(0, label, pending)
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

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
