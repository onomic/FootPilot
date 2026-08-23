package com.onomic.footpilot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import java.util.Locale

/**
 * Tracks every BluetoothGatt the app opens (live connection, on-demand checks, polling),
 * so a single Disconnect can drop them all — even one a Check left behind that the live
 * connection's own disconnect would never reach.
 */
object BleRegistry {
    private val open = mutableMapOf<BluetoothGatt, String>()

    fun add(g: BluetoothGatt, targetAddress: String) {
        synchronized(open) {
            open[g] = targetAddress.trim().uppercase(Locale.US)
        }
    }

    fun remove(g: BluetoothGatt) {
        synchronized(open) { open.remove(g) }
    }

    fun count(): Int = synchronized(open) { open.size }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        val clients = synchronized(open) { open.keys.toList() }
        for (g in clients) {
            try { g.disconnect() } catch (_: Exception) {}
        }
    }

    /** One-time hard recovery for one target; normal temporary cleanup closes its own GATT. */
    @SuppressLint("MissingPermission")
    fun closeTarget(targetAddress: String): Int {
        val normalized = targetAddress.trim().uppercase(Locale.US)
        val clients = synchronized(open) {
            open.filterValues { it == normalized }.keys.toList().also { matches ->
                matches.forEach(open::remove)
            }
        }
        for (g in clients) {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
        return clients.size
    }

    @SuppressLint("MissingPermission")
    fun closeAll() {
        val clients = synchronized(open) {
            open.keys.toList().also { open.clear() }
        }
        for (g in clients) {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
    }
}
