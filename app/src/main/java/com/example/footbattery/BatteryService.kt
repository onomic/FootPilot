package com.example.footbattery

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Live, continuous monitoring: holds a persistent BLE link, shows an ongoing
 * notification, and alerts when the level drops below the threshold.
 * Background polling (intermittent) is handled separately by BatteryReadWorker.
 */
class BatteryService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.footbattery.STOP"
        const val ACTION_CHECK = "com.example.footbattery.CHECK"

        /** Default threshold; the live value is stored in Prefs and adjustable in Settings. */
        const val LOW_BATTERY_THRESHOLD = 25

        /** The only device this app will connect to. Change these to retarget. */
        const val TARGET_ADDRESS = "CA:AD:73:A4:52:80"
        const val TARGET_NAME = "HF206250"
    }

    private var gatt: BluetoothGatt? = null
    private var userStopping = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Alerts.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopLive(); return START_NOT_STICKY }
            ACTION_CHECK -> { doReread(); return START_STICKY }
            else -> {
                // null intent = system restart; only resume if the user had it on.
                if (intent?.action == null && !Prefs.monitoring(this)) {
                    stopSelf(); return START_NOT_STICKY
                }
                startLive()
                return START_STICKY
            }
        }
    }

    private fun startLive() {
        Prefs.setMonitoring(this, true)
        userStopping = false
        ServiceCompat.startForeground(
            this, Alerts.ONGOING_ID, buildOngoing("Connecting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
        BatteryRepo.running.value = true
        BatteryRepo.status.value = "Connecting…"
        connect()
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = try {
            adapter.getRemoteDevice(TARGET_ADDRESS)
        } catch (e: Exception) {
            BatteryRepo.status.value = "Bad device address"
            stopSelf(); return
        }
        // autoConnect = true keeps the link resilient: it re-establishes when the foot
        // comes back in range, until the user explicitly disconnects.
        gatt = device.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun doReread() {
        val g = gatt ?: return
        val ch = g.getService(Uuids.SERVICE)?.getCharacteristic(Uuids.LEVEL) ?: return
        g.readCharacteristic(ch)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    BatteryRepo.status.value = "Connected"
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (userStopping) {
                        handler.post { forceCleanupAndStop() }
                    } else {
                        BatteryRepo.status.value = "Waiting for foot…"
                        updateOngoing()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(Uuids.SERVICE)?.getCharacteristic(Uuids.LEVEL)
            if (ch == null) {
                BatteryRepo.status.value = "No battery service found"
                return
            }
            g.readCharacteristic(ch)
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(Uuids.CCCD)?.let { d ->
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) = handle(c, value)

        @Deprecated("Deprecated in API 33") @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) = handle(c, c.value)

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handle(c, value)

        @Deprecated("Deprecated in API 33") @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
            handle(c, c.value)
    }

    private fun handle(c: BluetoothGattCharacteristic, value: ByteArray?) {
        if (c.uuid != Uuids.LEVEL || value == null || value.isEmpty()) return
        val pct = value[0].toInt() and 0xFF
        BatteryRepo.level.value = pct
        BatteryRepo.status.value = "Connected"
        updateOngoing()
        Alerts.recordReading(this, pct)
    }

    // ---- Ongoing notification ----

    private fun openApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, i, flags)
    }

    private fun stopAction(): NotificationCompat.Action {
        val i = Intent(this, BatteryService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getService(this, 1, i, flags)
        return NotificationCompat.Action(0, "Disconnect", pi)
    }

    private fun buildOngoing(text: String): Notification {
        val lvl = BatteryRepo.level.value
        val title = if (lvl != null) "Battery $lvl%" else "Battery —"
        return NotificationCompat.Builder(this, Alerts.ONGOING_CH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(stopAction())
            .build()
    }

    private fun updateOngoing() {
        getSystemService(NotificationManager::class.java)
            .notify(Alerts.ONGOING_ID, buildOngoing(BatteryRepo.status.value))
    }

    // ---- Lifecycle / true disconnect ----

    @SuppressLint("MissingPermission")
    private fun stopLive() {
        Prefs.setMonitoring(this, false)
        userStopping = true
        val g = gatt
        if (g != null) {
            try { g.disconnect() } catch (_: Exception) {}
            // Fallback in case the disconnect callback never arrives.
            handler.postDelayed({ forceCleanupAndStop() }, 1500)
        } else {
            forceCleanupAndStop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun forceCleanupAndStop() {
        handler.removeCallbacksAndMessages(null)
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        BatteryRepo.running.value = false
        BatteryRepo.status.value = "Disconnected"
        getSystemService(NotificationManager::class.java).cancel(Alerts.ALERT_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        BatteryRepo.running.value = false
    }
}
