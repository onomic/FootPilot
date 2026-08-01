package com.example.footbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Last-resort disconnect: force-removes any bond to the foot via the hidden
 * BluetoothDevice.removeBond() method (reflection). On Android, removeBond() also tears
 * the active link fully down, which reliably drops the device when a normal
 * disconnect()/close() doesn't. Used only on the explicit Disconnect action.
 *
 * Note: this is a hidden API, so it may be a no-op on some OEM builds; it's wrapped to
 * fail silently. It does not require the device to actually be bonded.
 */
object BondHelper {
    @SuppressLint("MissingPermission")
    fun forceUnbond(ctx: Context) {
        try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return
            val device: BluetoothDevice = adapter.getRemoteDevice(FootConfig.TARGET_ADDRESS)
            val m = device.javaClass.getMethod("removeBond")
            m.invoke(device)
        } catch (_: Exception) {
            // Hidden API unavailable or blocked on this build — ignore.
        }
    }
}
