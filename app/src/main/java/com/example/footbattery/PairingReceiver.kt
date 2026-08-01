package com.example.footbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-answers the system's BLE pairing request with the PIN saved in Settings, so the
 * user doesn't have to type it on every reconnect. Only acts for our target foot and only
 * if a code has been saved; otherwise it stays out of the way and Android shows its dialog.
 */
class PairingReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return

        val device: BluetoothDevice? =
            if (Build_VERSION_TIRAMISU_OR_HIGHER)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

        // Only handle our foot.
        if (device == null || device.address != FootConfig.TARGET_ADDRESS) return

        val code = Prefs.pairingCode(context)
        if (code.isEmpty()) return  // nothing saved -> let Android prompt normally

        val variant = intent.getIntExtra(
            BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR
        )

        try {
            when (variant) {
                // Numeric/alphanumeric PIN entry.
                BluetoothDevice.PAIRING_VARIANT_PIN -> {
                    val bytes = code.toByteArray(Charsets.UTF_8)
                    device.setPin(bytes)
                    abortBroadcast()  // suppress the system dialog
                }
                // Just-works / passkey-confirm style: confirm automatically.
                else -> {
                    try {
                        val m = device.javaClass.getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType)
                        m.invoke(device, true)
                        abortBroadcast()
                    } catch (_: Exception) { /* leave to system dialog */ }
                }
            }
        } catch (_: Exception) {
            // If anything fails, do nothing and let Android handle pairing.
        }
    }

    private val Build_VERSION_TIRAMISU_OR_HIGHER: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
}
