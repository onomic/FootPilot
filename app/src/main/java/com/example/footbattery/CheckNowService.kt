package com.example.footbattery

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground executor for notification Check now and standby actions. */
class CheckNowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeStarts = AtomicInteger(0)
    private val latestStartId = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val operationText = when (action) {
            Alerts.ACTION_CHECK_NOW -> "Checking..."
            Alerts.ACTION_STANDBY_ON -> "Turning standby on..."
            Alerts.ACTION_STANDBY_OFF -> "Turning standby off..."
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val ctx = applicationContext
        BatteryRepo.ensureInitialized(ctx)
        Alerts.ensureChannels(ctx)
        val liveMonitoring = LiveConnection.isMonitoringRequested()
        if (!liveMonitoring) Alerts.cancelOngoing(ctx)
        val notificationId = if (liveMonitoring) {
            Alerts.ONGOING_ID
        } else {
            Alerts.POLL_STATUS_ID
        }
        ServiceCompat.startForeground(
            this,
            notificationId,
            Alerts.operationNotification(ctx, operationText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
        Alerts.showOperation(ctx, operationText)
        latestStartId.set(startId)
        activeStarts.incrementAndGet()

        scope.launch {
            try {
                val result = when (action) {
                    Alerts.ACTION_CHECK_NOW -> FootOperations.checkNow(ctx, CheckOrigin.NOTIFICATION)
                    Alerts.ACTION_STANDBY_ON -> FootOperations.changeStandby(ctx, StandbyState.ON)
                    Alerts.ACTION_STANDBY_OFF -> FootOperations.changeStandby(ctx, StandbyState.OFF)
                    else -> FootOperationResult.Failed("Unknown operation")
                }
                if (result is FootOperationResult.Busy) {
                    val active = BleOperationCoordinator.state.value.visibleOperation?.statusText
                    if (active != null) Alerts.showOperation(ctx, active)
                }
            } finally {
                if (activeStarts.decrementAndGet() == 0) {
                    if (!LiveConnection.isMonitoringRequested() && !Prefs.polling(ctx)) {
                        Alerts.cancelPollStatus(ctx)
                    }
                    ServiceCompat.stopForeground(
                        this@CheckNowService,
                        ServiceCompat.STOP_FOREGROUND_DETACH
                    )
                    stopSelf(latestStartId.get())
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
