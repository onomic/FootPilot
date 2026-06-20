package com.example.footbattery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One-shot: connect, read the battery, then disconnect CLEANLY (wait for the link to
 * actually drop before closing, so the foot receives the disconnect and doesn't linger),
 * and return the percentage (or null on failure/timeout).
 */
object BleReader {

    // Prevents overlapping reads. A second call while one is in flight returns null
    // immediately instead of starting a competing connection (the foot allows only one).
    private val inFlight = AtomicBoolean(false)

    fun isBusy(): Boolean = inFlight.get()

    @SuppressLint("MissingPermission")
    suspend fun readOnce(ctx: Context): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return null
        if (!adapter.isEnabled) return null

        // Reject a second concurrent read rather than racing connections.
        if (!inFlight.compareAndSet(false, true)) return null

        val device = try {
            adapter.getRemoteDevice(BatteryService.TARGET_ADDRESS)
        } catch (e: Exception) {
            inFlight.set(false)
            return null
        }

        val result = withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                var gattRef: BluetoothGatt? = null
                var readValue: Int? = null
                val done = AtomicBoolean(false)

                // Closes the client and returns the value. When monitoring is OFF, also
                // force-unbond so the foot fully drops (matching the prior in-app behavior).
                // No artificial delay — the unbond itself forces the teardown.
                fun finishAndClose(value: Int?) {
                    if (done.compareAndSet(false, true)) {
                        main.removeCallbacksAndMessages(null)
                        gattRef?.let { BleRegistry.remove(it) }
                        try { gattRef?.close() } catch (_: Exception) {}
                        if (!BatteryRepo.running.value) {
                            // Monitoring off -> release the bond immediately.
                            BondHelper.forceUnbond(ctx)
                        }
                        if (cont.isActive) cont.resume(value)
                    }
                }

                // Got the value: tear down now. disconnect() asks politely; the unbond in
                // finishAndClose forces it. No 1.5s wait, so there's no delay.
                fun beginDisconnect(value: Int?) {
                    readValue = value
                    try { gattRef?.disconnect() } catch (_: Exception) {}
                    finishAndClose(value)
                }

                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> finishAndClose(readValue)
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val ch = g.getService(Uuids.SERVICE)?.getCharacteristic(Uuids.LEVEL)
                        if (ch == null) beginDisconnect(null) else g.readCharacteristic(ch)
                    }

                    override fun onCharacteristicRead(
                        g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
                    ) {
                        // ONLY trust the value on success; a failed read can carry garbage (e.g. 0).
                        val v = if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty())
                            value[0].toInt() and 0xFF else null
                        beginDisconnect(v)
                    }

                    @Deprecated("Deprecated in API 33") @Suppress("DEPRECATION")
                    override fun onCharacteristicRead(
                        g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
                    ) {
                        val raw = c.value
                        val v = if (status == BluetoothGatt.GATT_SUCCESS && raw != null && raw.isNotEmpty())
                            raw[0].toInt() and 0xFF else null
                        beginDisconnect(v)
                    }
                }

                gattRef = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                gattRef?.let { BleRegistry.add(it) }
                cont.invokeOnCancellation {
                    try { gattRef?.disconnect() } catch (_: Exception) {}
                    finishAndClose(null)
                }
            }
        }
        inFlight.set(false)
        return result
    }
}
