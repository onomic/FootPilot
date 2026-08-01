package com.example.footbattery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class BleSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class FullSnapshotRead(
    val batteryLevel: Int?,
    val standby: StandbyState?,
    val batteryError: String? = null,
    val standbyError: String? = null
)

/**
 * One callback-driven GATT client. Android permits only one outstanding GATT operation;
 * [operationMutex] plus the matching callback deferred enforce that rule for every read,
 * write, and descriptor write.
 */
class FootGattSession(
    context: Context,
    private val onBatteryNotification: (Int) -> Unit = {},
    private val onUnexpectedDisconnect: (String) -> Unit = {}
) {
    companion object {
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val GATT_OPERATION_TIMEOUT_MS = 5_000L
        private const val COMMAND_RESPONSE_TIMEOUT_MS = 3_000L
    }

    private enum class OperationType { DESCRIPTOR_WRITE, CHARACTERISTIC_WRITE, CHARACTERISTIC_READ }

    private data class GattResult(val status: Int, val value: ByteArray? = null)

    private data class PendingOperation(
        val type: OperationType,
        val characteristicUuid: UUID,
        val deferred: CompletableDeferred<GattResult>
    )

    private class ResponseTimeout(val observed: StandbyResponse?) : Exception()

    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val transactionMutex = Mutex()
    private val pendingGuard = Any()
    private var pending: PendingOperation? = null
    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()
    private val aa01Notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private val closing = AtomicBoolean(false)
    private val poisoned = AtomicBoolean(false)
    private val disconnectReported = AtomicBoolean(false)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var initialized = false
    @Volatile private var batteryNotificationsEnabled = false
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var aa01Characteristic: BluetoothGattCharacteristic? = null
    private var aa02Characteristic: BluetoothGattCharacteristic? = null

    fun isUsable(): Boolean = initialized && !closing.get() && !poisoned.get() && gatt != null

    @SuppressLint("MissingPermission")
    suspend fun connectAndInitialize(onProgress: (LiveConnectionState) -> Unit = {}) {
        checkConnectPermission()
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: throw BleSessionException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw BleSessionException("Turn on Bluetooth")
        val device = try {
            adapter.getRemoteDevice(FootConfig.TARGET_ADDRESS)
        } catch (e: Exception) {
            throw BleSessionException("The saved foot address is invalid", e)
        }

        try {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                onProgress(LiveConnectionState.CONNECTING)
                val client = try {
                    device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: Exception) {
                    throw BleSessionException("Could not start the Bluetooth connection", e)
                } ?: throw BleSessionException("Could not start the Bluetooth connection")
                gatt = client
                BleRegistry.add(client)

                connected.await()
                onProgress(LiveConnectionState.DISCOVERING)
                if (!client.discoverServices()) {
                    throw BleSessionException("Could not start service discovery")
                }
                servicesDiscovered.await()
                resolveCharacteristics(client)

                onProgress(LiveConnectionState.INITIALIZING)
                enableNotifications(requireNotNull(aa01Characteristic) { "AA01 not resolved" })
                enableNotifications(requireNotNull(aa02Characteristic) { "AA02 not resolved" })
                initialized = true
            }
        } catch (e: TimeoutCancellationException) {
            throw BleSessionException("Bluetooth connection timed out", e)
        } catch (e: IllegalArgumentException) {
            throw BleSessionException(e.message ?: "Required Bluetooth characteristic is missing", e)
        }
    }

    suspend fun readFullSnapshot(): FullSnapshotRead = transactionMutex.withLock {
        ensureUsable()
        var standby: StandbyState? = null
        var standbyError: String? = null
        try {
            standby = requireResponse(
                exchangeStandby(
                    StandbyProtocol.queryCommand(),
                    StandbyResponseKind.QUERY
                )
            ).state
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            standbyError = e.userMessage("Standby check failed")
        }

        var battery: Int? = null
        var batteryError: String? = null
        try {
            battery = readBatteryInternal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            batteryError = e.userMessage("Battery check failed")
        }
        FullSnapshotRead(battery, standby, batteryError, standbyError)
    }

    /** Live-only subscription, performed after the required initial full snapshot operation. */
    suspend fun enableBatteryMonitoring() = transactionMutex.withLock {
        ensureUsable()
        if (!batteryNotificationsEnabled) {
            enableNotifications(
                batteryCharacteristic ?: throw BleSessionException("Battery level is unavailable")
            )
            batteryNotificationsEnabled = true
        }
    }

    /** Query, optional set, typed final query, then battery read. */
    suspend fun changeStandby(requested: StandbyState): StandbyTransactionRead =
        transactionMutex.withLock {
            require(requested != StandbyState.UNKNOWN)
            ensureUsable()
            StandbyTransaction.execute(
                requested,
                object : StandbyTransactionTransport {
                    override suspend fun exchange(
                        command: ByteArray,
                        expectedKind: StandbyResponseKind,
                        expectedState: StandbyState?
                    ): StandbyCommandExchangeResult = exchangeStandby(
                        command,
                        expectedKind,
                        expectedState
                    )

                    override suspend fun readBattery(): StandbyBatteryReadResult = try {
                        StandbyBatteryReadResult.Success(readBatteryInternal())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StandbyBatteryReadResult.Failed(e.userMessage("Battery check failed"))
                    }
                }
            )
        }

    @SuppressLint("MissingPermission")
    fun requestDisconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun disconnectAndClose(removeBond: Boolean) {
        if (!closing.compareAndSet(false, true)) return
        initialized = false
        batteryNotificationsEnabled = false
        failPending(BleSessionException("Bluetooth session closed"))
        aa01Notifications.close()
        val client = gatt
        gatt = null
        if (client != null) {
            try { client.disconnect() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            BleRegistry.remove(client)
        }
        if (removeBond) BondHelper.forceUnbond(appContext)
    }

    private fun checkConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BleSessionException("Bluetooth permission is required")
        }
    }

    private fun resolveCharacteristics(client: BluetoothGatt) {
        batteryCharacteristic = client.getService(Uuids.SERVICE)?.getCharacteristic(Uuids.LEVEL)
            ?: throw BleSessionException("Battery service was not found")
        val ossur = client.getService(Uuids.OSSUR_SERVICE)
            ?: throw BleSessionException("Össur standby service was not found")
        aa01Characteristic = ossur.getCharacteristic(Uuids.AA01)
            ?: throw BleSessionException("Össur command characteristic AA01 was not found")
        aa02Characteristic = ossur.getCharacteristic(Uuids.AA02)
            ?: throw BleSessionException("Össur streaming characteristic AA02 was not found")
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val client = requireGatt()
        if (!client.setCharacteristicNotification(characteristic, true)) {
            throw BleSessionException("Could not enable ${characteristic.uuid} notifications")
        }
        val descriptor = characteristic.getDescriptor(Uuids.CCCD)
            ?: throw BleSessionException("CCCD missing for ${characteristic.uuid}")
        runGattOperation(OperationType.DESCRIPTOR_WRITE, characteristic.uuid) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                client.writeDescriptor(descriptor)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeAa01(value: ByteArray) {
        val client = requireGatt()
        val characteristic = aa01Characteristic ?: throw BleSessionException("AA01 is unavailable")
        runGattOperation(OperationType.CHARACTERISTIC_WRITE, Uuids.AA01) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                client.writeCharacteristic(characteristic)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readBatteryInternal(): Int {
        val client = requireGatt()
        val characteristic = batteryCharacteristic ?: throw BleSessionException("Battery level is unavailable")
        val result = runGattOperation(OperationType.CHARACTERISTIC_READ, Uuids.LEVEL) {
            client.readCharacteristic(characteristic)
        }
        val value = result.value?.firstOrNull()?.toInt()?.and(0xFF)
            ?: throw BleSessionException("Battery response was empty")
        if (value !in 0..100) throw BleSessionException("Battery response was invalid")
        return value
    }

    private suspend fun exchangeStandby(
        command: ByteArray,
        expectedKind: StandbyResponseKind,
        expectedState: StandbyState? = null
    ): StandbyCommandExchangeResult {
        while (aa01Notifications.tryReceive().isSuccess) {
            // Drop stale responses before this command is written.
        }
        try {
            writeAa01(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StandbyCommandExchangeResult.WriteFailed(
                e.userMessage("Bluetooth command write failed")
            )
        }

        var observed: StandbyResponse? = null
        try {
            val response = withTimeout(COMMAND_RESPONSE_TIMEOUT_MS) {
                while (true) {
                    val parsed = StandbyProtocol.parseResponse(aa01Notifications.receive())
                    if (parsed?.kind == expectedKind) {
                        observed = parsed
                        if (StandbyProtocol.matches(parsed, expectedKind, expectedState)) {
                            return@withTimeout parsed
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            return StandbyCommandExchangeResult.Response(response)
        } catch (_: TimeoutCancellationException) {
            return StandbyCommandExchangeResult.ResponseMissing(
                ResponseTimeout(observed).userMessage("Standby response failed")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StandbyCommandExchangeResult.ResponseMissing(
                e.userMessage("Standby response failed")
            )
        }
    }

    private fun requireResponse(result: StandbyCommandExchangeResult): StandbyResponse =
        when (result) {
            is StandbyCommandExchangeResult.Response -> result.response
            is StandbyCommandExchangeResult.WriteFailed -> throw BleSessionException(result.message)
            is StandbyCommandExchangeResult.ResponseMissing -> throw BleSessionException(result.message)
        }

    private suspend fun runGattOperation(
        type: OperationType,
        characteristicUuid: UUID,
        start: () -> Boolean
    ): GattResult = operationMutex.withLock {
        ensureUsableOrInitializing()
        val deferred = CompletableDeferred<GattResult>()
        val operation = PendingOperation(type, characteristicUuid, deferred)
        synchronized(pendingGuard) {
            check(pending == null) { "A GATT operation is already pending" }
            pending = operation
        }

        try {
            val started = try {
                start()
            } catch (e: Exception) {
                throw BleSessionException("Could not start Bluetooth operation", e)
            }
            if (!started) throw BleSessionException("Android rejected the Bluetooth operation")

            val result = try {
                withTimeout(GATT_OPERATION_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw BleSessionException("Bluetooth operation timed out", e)
            }
            if (result.status != BluetoothGatt.GATT_SUCCESS) {
                throw BleSessionException("Bluetooth operation failed (${result.status})")
            }
            result
        } catch (e: Exception) {
            poisoned.set(true)
            reportFatalFailure(e.message ?: "Bluetooth operation failed")
            throw e
        } finally {
            synchronized(pendingGuard) {
                if (pending === operation) pending = null
            }
        }
    }

    private fun ensureUsable() {
        if (!isUsable()) throw BleSessionException("Bluetooth connection is not ready")
    }

    private fun ensureUsableOrInitializing() {
        if (closing.get() || poisoned.get() || gatt == null) {
            throw BleSessionException("Bluetooth connection is closed")
        }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw BleSessionException("Bluetooth connection is closed")

    private fun completePending(
        type: OperationType,
        characteristicUuid: UUID,
        status: Int,
        value: ByteArray? = null
    ) {
        val match = synchronized(pendingGuard) {
            pending?.takeIf { it.type == type && it.characteristicUuid == characteristicUuid }
        } ?: return
        match.deferred.complete(GattResult(status, value?.copyOf()))
    }

    private fun failPending(error: Throwable) {
        val operation = synchronized(pendingGuard) { pending }
        operation?.deferred?.completeExceptionally(error)
        connected.completeExceptionally(error)
        servicesDiscovered.completeExceptionally(error)
    }

    private fun reportFatalFailure(message: String) {
        if (!closing.get() && disconnectReported.compareAndSet(false, true)) {
            onUnexpectedDisconnect(message)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val message = if (status == BluetoothGatt.GATT_SUCCESS) {
                    "Foot disconnected"
                } else {
                    "Bluetooth connection failed ($status)"
                }
                val error = BleSessionException(message)
                poisoned.set(true)
                failPending(error)
                reportFatalFailure(message)
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDiscovered.complete(Unit)
            } else {
                servicesDiscovered.completeExceptionally(
                    BleSessionException("Service discovery failed ($status)")
                )
            }
        }

        override fun onDescriptorWrite(
            client: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            completePending(
                OperationType.DESCRIPTOR_WRITE,
                descriptor.characteristic.uuid,
                status
            )
        }

        override fun onCharacteristicWrite(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completePending(OperationType.CHARACTERISTIC_WRITE, characteristic.uuid, status)
        }

        override fun onCharacteristicRead(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            completePending(OperationType.CHARACTERISTIC_READ, characteristic.uuid, status, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completePending(
                OperationType.CHARACTERISTIC_READ,
                characteristic.uuid,
                status,
                characteristic.value
            )
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleNotification(characteristic.uuid, characteristic.value)
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray?) {
        if (value == null) return
        when (uuid) {
            Uuids.AA01 -> aa01Notifications.trySend(value.copyOf())
            Uuids.LEVEL -> {
                val level = value.firstOrNull()?.toInt()?.and(0xFF)
                if (level != null && level in 0..100) onBatteryNotification(level)
            }
            // AA02 is deliberately subscribed for device initialization/telemetry but ignored.
            Uuids.AA02 -> Unit
        }
    }

    private fun Exception.userMessage(fallback: String): String = when (this) {
        is ResponseTimeout -> if (observed == null) "$fallback: response timed out" else
            "$fallback: foot reported ${observed.state.displayName()}"
        is BleSessionException -> message ?: fallback
        else -> fallback
    }
}

fun StandbyState.displayName(): String = when (this) {
    StandbyState.ON -> "on"
    StandbyState.OFF -> "off"
    StandbyState.UNKNOWN -> "unknown"
}
