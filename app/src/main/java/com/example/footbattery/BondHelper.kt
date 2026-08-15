package com.example.footbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Isolates the hidden BluetoothDevice.removeBond() call and the target-specific observation needed
 * to know whether Android actually completed it. Temporary release and explicit Disconnect retain
 * the existing force-unbond policy; callers can now distinguish an accepted asynchronous request
 * from already-unbonded and failed API states.
 */
object BondHelper {
    private const val TAG = "FootPilotBle"

    @SuppressLint("MissingPermission")
    internal fun forceUnbond(ctx: Context, target: SelectedFoot): BondRemovalRequestOutcome {
        val device = resolveDevice(ctx.applicationContext, target)
            ?: return BondRemovalRequestOutcome.Failed("Bluetooth target is unavailable for unbond")
        return requestRemoval(device)
    }

    internal fun observeRelease(
        ctx: Context,
        target: SelectedFoot,
        timeoutMs: Long
    ): BondReleaseObservation = AndroidBondReleaseObservation(
        appContext = ctx.applicationContext,
        target = target,
        timeoutMs = timeoutMs
    )

    @SuppressLint("MissingPermission")
    private fun requestRemoval(device: BluetoothDevice): BondRemovalRequestOutcome {
        val current = try {
            device.bondState
        } catch (_: Exception) {
            return BondRemovalRequestOutcome.Failed("Bluetooth bond state is unavailable")
        }
        if (current == BluetoothDevice.BOND_NONE) {
            debug("UNBOND already none")
            return BondRemovalRequestOutcome.AlreadyUnbonded
        }

        return try {
            val method = device.javaClass.getMethod("removeBond")
            if (method.invoke(device) as? Boolean == true) {
                debug("UNBOND requested")
                BondRemovalRequestOutcome.Requested
            } else {
                debug("UNBOND request failed")
                BondRemovalRequestOutcome.Failed("Android rejected the unbond request")
            }
        } catch (_: Exception) {
            debug("UNBOND API unavailable")
            BondRemovalRequestOutcome.Failed("Unbond API is unavailable")
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDevice(ctx: Context, target: SelectedFoot): BluetoothDevice? {
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return null
            adapter.getRemoteDevice(target.address)
        } catch (_: Exception) {
            null
        }
    }

    private class AndroidBondReleaseObservation(
        private val appContext: Context,
        target: SelectedFoot,
        private val timeoutMs: Long
    ) : BondReleaseObservation {
        private val signal = TargetBondReleaseSignal(target.address)
        private val closed = AtomicBoolean(false)
        private val device = resolveDevice(appContext, target)
        private var registered = false
        private var registrationFailure: String? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changedDevice = intent.bluetoothDevice() ?: return
                val address = try {
                    changedDevice.address
                } catch (_: Exception) {
                    return
                }
                val state = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR
                ).toTargetBondState()
                signal.onBondStateChanged(address, state)
            }
        }

        init {
            if (device == null) {
                registrationFailure = "Bluetooth target is unavailable for bond observation"
            } else {
                try {
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                        ContextCompat.RECEIVER_EXPORTED
                    )
                    registered = true
                } catch (_: Exception) {
                    registrationFailure = "Bond-state observer is unavailable"
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun currentState(): TargetBondState {
            if (signal.isReleased()) return TargetBondState.UNBONDED
            val current = try {
                device?.bondState
            } catch (_: Exception) {
                null
            }
            return current.toTargetBondState()
        }

        override fun requestRemoval(): BondRemovalRequestOutcome {
            val currentDevice = device
                ?: return BondRemovalRequestOutcome.Failed(
                    "Bluetooth target is unavailable for unbond"
                )
            return requestRemoval(currentDevice)
        }

        override suspend fun awaitUnbonded(): TargetReleaseOutcome {
            if (currentState() == TargetBondState.UNBONDED) {
                return TargetReleaseOutcome.Complete
            }
            registrationFailure?.let { return TargetReleaseOutcome.Uncertain(it) }
            return try {
                withTimeout(timeoutMs) { signal.await() }
                debug("BOND_NONE observed")
                TargetReleaseOutcome.Complete
            } catch (_: TimeoutCancellationException) {
                if (currentState() == TargetBondState.UNBONDED) {
                    debug("BOND_NONE observed by final state check")
                    TargetReleaseOutcome.Complete
                } else {
                    debug("BOND_NONE timed out")
                    TargetReleaseOutcome.Uncertain("bond release event timed out")
                }
            } catch (e: CancellationException) {
                throw e
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true) || !registered) return
            registered = false
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // The observer is already absent; release remains idempotent.
            }
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun Int?.toTargetBondState(): TargetBondState = when (this) {
        BluetoothDevice.BOND_NONE -> TargetBondState.UNBONDED
        BluetoothDevice.BOND_BONDED,
        BluetoothDevice.BOND_BONDING -> TargetBondState.BONDED_OR_BONDING
        else -> TargetBondState.UNKNOWN
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
