package com.example.footbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Holds ONE live BLE connection for the whole app session and toggles it, rather than
 * closing and recreating it each time. Closing + re-creating the GATT client every cycle
 * is what made disconnect "work once, then never": the reused client never re-registers
 * cleanly. Here we connect once, then use disconnect()/connect() to toggle. disconnect()
 * sends the link-terminate the foot needs to actually drop (and chirp). The client is only
 * truly closed when the app process ends.
 */
object LiveConnection {

    private var gatt: BluetoothGatt? = null
    private var appCtx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wantConnected = false

    fun isConnected(): Boolean = gatt != null && BatteryRepo.running.value

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app

        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { BatteryRepo.status.value = "No Bluetooth"; return }
        if (!adapter.isEnabled) { BatteryRepo.status.value = "Turn on Bluetooth"; return }

        Alerts.ensureChannels(app)
        Prefs.setMonitoring(app, true)
        wantConnected = true
        BatteryRepo.running.value = true
        BatteryRepo.status.value = "Connecting…"

        // stop() fully closes the client, so always build a fresh one here.
        val device = try {
            adapter.getRemoteDevice(BatteryService.TARGET_ADDRESS)
        } catch (e: Exception) {
            BatteryRepo.status.value = "Bad device address"
            BatteryRepo.running.value = false
            return
        }
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt?.let { BleRegistry.add(it) }
    }

    /** Force a fresh read on the existing live connection. */
    @SuppressLint("MissingPermission")
    fun refresh() {
        val g = gatt ?: return
        val ch = g.getService(Uuids.SERVICE)?.getCharacteristic(Uuids.LEVEL) ?: return
        g.readCharacteristic(ch)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        appCtx?.let { Prefs.setMonitoring(it, false) }
        wantConnected = false
        BatteryRepo.status.value = "Disconnecting…"
        appCtx?.let { Alerts.cancelOngoing(it) }

        // Send disconnect to every tracked client so the foot receives the link-terminate
        // (and chirps), then fully CLOSE them all and clear. Closing — not just
        // disconnecting — releases the stack so the next Start builds a clean client.
        val live = gatt
        gatt = null
        try { live?.disconnect() } catch (_: Exception) {}
        BleRegistry.disconnectAll()
        // Brief beat so the terminate transmits, then close clients AND force-unbond as a
        // last-resort teardown that reliably drops the foot when close() alone doesn't.
        val ctx = appCtx
        handler.postDelayed({
            BleRegistry.closeAll()
            ctx?.let { BondHelper.forceUnbond(it) }
            BatteryRepo.running.value = false
            BatteryRepo.status.value = "Disconnected"
        }, 600)
        BatteryRepo.running.value = false
        BatteryRepo.status.value = "Disconnected"
    }

    /** Fully release the BLE client. Optional; the OS also frees it on process death. */
    @SuppressLint("MissingPermission")
    fun release() {
        wantConnected = false
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        BatteryRepo.running.value = false
        appCtx?.let { Alerts.cancelOngoing(it) }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Ignore callbacks from any client we're no longer tracking (and release it).
            if (g !== gatt) { BleRegistry.remove(g); try { g.close() } catch (_: Exception) {}; return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    BatteryRepo.running.value = true
                    BatteryRepo.status.value = "Connected"
                    appCtx?.let { Alerts.postOngoing(it, BatteryRepo.level.value, "Connected") }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    BatteryRepo.running.value = false
                    appCtx?.let { Alerts.cancelOngoing(it) }
                    BatteryRepo.status.value = if (wantConnected) "Foot disconnected" else "Disconnected"
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
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
        appCtx?.let {
            Alerts.postOngoing(it, pct, "Connected")
            Alerts.recordReading(it, pct)
        }
    }
}
