package com.example.footbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import java.util.Collections

/**
 * Tracks every BluetoothGatt the app opens (live connection, on-demand checks, polling),
 * so a single Disconnect can drop them all — even one a Check left behind that the live
 * connection's own disconnect would never reach.
 */
object BleRegistry {
    private val open = Collections.synchronizedSet(mutableSetOf<BluetoothGatt>())

    fun add(g: BluetoothGatt) { open.add(g) }
    fun remove(g: BluetoothGatt) { open.remove(g) }
    fun count(): Int = open.size

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        synchronized(open) {
            for (g in open.toList()) {
                try { g.disconnect() } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun closeAll() {
        synchronized(open) {
            for (g in open.toList()) {
                try { g.disconnect() } catch (_: Exception) {}
                try { g.close() } catch (_: Exception) {}
            }
            open.clear()
        }
    }
}
