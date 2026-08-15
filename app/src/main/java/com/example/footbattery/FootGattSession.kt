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
import android.util.Log
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

open class BleSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One callback-driven GATT client. Android permits only one outstanding GATT operation;
 * [operationMutex] plus the matching callback deferred enforce that rule for every read,
 * write, and descriptor write.
 */
class FootGattSession(
    context: Context,
    val target: SelectedFoot,
    private val onBatteryNotification: (Int) -> Unit = {},
    private val onUnexpectedDisconnect: (String) -> Unit = {}
) {
    companion object {
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val GATT_OPERATION_TIMEOUT_MS = 5_000L
        private const val COMMAND_RESPONSE_TIMEOUT_MS = 3_000L
        private const val DISCONNECT_RELEASE_TIMEOUT_MS = 5_000L
        private const val TAG = "FootPilotBle"
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
    private val disconnected = CompletableDeferred<Unit>()
    private val closeOutcome = CompletableDeferred<TargetReleaseOutcome>()
    private val aa01Events = Channel<Aa01Event>(Channel.UNLIMITED)
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

    suspend fun connectAndInitialize(onProgress: (LiveConnectionState) -> Unit = {}) =
        connectAndResolveProfile(initializeNotifications = true, onProgress = onProgress)

    /** Setup-only compatibility check: service discovery and profile validation, with no command. */
    suspend fun connectAndVerifyProfile() =
        connectAndResolveProfile(initializeNotifications = false, onProgress = {})

    @SuppressLint("MissingPermission")
    private suspend fun connectAndResolveProfile(
        initializeNotifications: Boolean,
        onProgress: (LiveConnectionState) -> Unit
    ) {
        checkConnectPermission()
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: throw BleSessionException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw BleSessionException("Turn on Bluetooth")
        val device = try {
            adapter.getRemoteDevice(target.address)
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
                BleRegistry.add(client, target.address)

                connected.await()
                onProgress(LiveConnectionState.DISCOVERING)
                if (!client.discoverServices()) {
                    throw BleSessionException("Could not start service discovery")
                }
                servicesDiscovered.await()
                resolveCharacteristics(client)

                if (initializeNotifications) {
                    onProgress(LiveConnectionState.INITIALIZING)
                    enableNotifications(requireNotNull(aa01Characteristic) { "AA01 not resolved" })
                    enableNotifications(requireNotNull(aa02Characteristic) { "AA02 not resolved" })
                }
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
        FullSnapshotTransaction.execute(
            object : FullSnapshotTransport {
                override suspend fun queryStandby(): FullSnapshotFieldRead<StandbyState> = try {
                    FullSnapshotFieldRead.Success(
                        requireResponse(
                            exchangeStandby(
                                StandbyProtocol.queryCommand(),
                                StandbyResponseKind.QUERY
                            )
                        ).state
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FullSnapshotFieldRead.Failed(e.userMessage("Standby check failed"))
                }

                override suspend fun queryAnkle(): FullSnapshotFieldRead<Int> = try {
                    FullSnapshotFieldRead.Success(
                        requireAnkleResponse(
                            exchangeAnkle(
                                AnkleProtocol.queryCommand(),
                                AnkleResponseKind.QUERY
                            )
                        ).millidegrees
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FullSnapshotFieldRead.Failed(e.userMessage("Ankle check failed"))
                }

                override suspend fun readBattery(): FullSnapshotFieldRead<Int> = try {
                    FullSnapshotFieldRead.Success(readBatteryInternal())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FullSnapshotFieldRead.Failed(e.userMessage("Battery check failed"))
                }
            }
        )
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

    /** Initial typed query, or optional set plus typed final query, then battery read. */
    suspend fun changeStandby(requested: StandbyState): StandbyTransactionRead =
        transactionMutex.withLock {
            require(requested != StandbyState.UNKNOWN)
            ensureUsable()
            StandbyTransaction.execute(requested, standbyTransport())
        }

    /** Notification action derives the opposite target from a fresh response on this session. */
    suspend fun toggleStandby(): StandbyTransactionRead = transactionMutex.withLock {
        ensureUsable()
        StandbyTransaction.executeToggle(standbyTransport())
    }

    suspend fun changeAnkle(
        request: AnkleTargetRequest,
        onPotentialMovement: () -> Unit = {}
    ): AnkleTransactionRead = transactionMutex.withLock {
        ensureUsable()
        AnkleTransaction.execute(request, ankleTransport(), onPotentialMovement)
    }

    suspend fun autoAlign(
        onOperation: (AnkleOperation) -> Unit = {},
        onPotentialMovement: () -> Unit = {}
    ): AutoAlignmentRead = transactionMutex.withLock {
        ensureUsable()
        AutoAlignmentTransaction.execute(
            transport = object : AutoAlignmentTransport {
                override suspend fun exchangeStandbyQuery(): StandbyCommandExchangeResult =
                    exchangeStandby(StandbyProtocol.queryCommand(), StandbyResponseKind.QUERY)

                override suspend fun exchangeAnkleQuery(): AnkleCommandExchangeResult =
                    exchangeAnkle(AnkleProtocol.queryCommand(), AnkleResponseKind.QUERY)

                override suspend fun writeStart(
                    onWriteAccepted: () -> Unit
                ): AutoStartWriteResult {
                    discardQueuedAa01Events()
                    return try {
                        writeAa01(
                            AutoAlignmentProtocol.startCommand(),
                            onWriteStarted = onWriteAccepted
                        )
                        AutoStartWriteResult.Accepted
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AutoStartWriteResult.Failed(e.userMessage("Bluetooth command write failed"))
                    }
                }

                override suspend fun awaitRelevantEvent(timeoutMs: Long): AutoEventWaitResult =
                    awaitAutoEvent(timeoutMs)
            },
            onOperation = onOperation,
            onPotentialMovement = onPotentialMovement
        )
    }

    @SuppressLint("MissingPermission")
    fun requestDisconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
    }

    /**
     * Temporary-session close path. The GATT stays owned until Android reports DISCONNECTED or the
     * bounded callback wait becomes explicitly uncertain; close/removal still happen on cancellation.
     */
    @SuppressLint("MissingPermission")
    suspend fun disconnectAndCloseAwaitingRelease(): TargetReleaseOutcome {
        if (!closing.compareAndSet(false, true)) return closeOutcome.await()
        val client = prepareClose()
        var outcome: TargetReleaseOutcome = TargetReleaseOutcome.Uncertain(
            "GATT disconnect release was interrupted"
        )
        try {
            outcome = when {
                client == null || disconnected.isCompleted -> TargetReleaseOutcome.AlreadyReleased
                else -> try {
                    client.disconnect()
                    try {
                        withTimeout(DISCONNECT_RELEASE_TIMEOUT_MS) { disconnected.await() }
                        debug("GATT_DISCONNECTED observed")
                        TargetReleaseOutcome.Complete
                    } catch (_: TimeoutCancellationException) {
                        debug("GATT_DISCONNECTED timed out")
                        TargetReleaseOutcome.Uncertain("GATT disconnect callback timed out")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    TargetReleaseOutcome.Uncertain("GATT disconnect request failed")
                }
            }
            return outcome
        } catch (e: CancellationException) {
            outcome = TargetReleaseOutcome.Uncertain("GATT disconnect wait was cancelled")
            throw e
        } finally {
            closeClient(client)
            closeOutcome.complete(outcome)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectAndClose(removeBond: Boolean) {
        if (!closing.compareAndSet(false, true)) return
        val client = prepareClose()
        var outcome: TargetReleaseOutcome = if (client == null || disconnected.isCompleted) {
            TargetReleaseOutcome.AlreadyReleased
        } else {
            TargetReleaseOutcome.Uncertain("GATT closed without awaiting disconnect")
        }
        try {
            if (client != null && !disconnected.isCompleted) {
                try { client.disconnect() } catch (_: Exception) {}
                if (disconnected.isCompleted) outcome = TargetReleaseOutcome.Complete
            }
        } finally {
            closeClient(client)
            closeOutcome.complete(outcome)
        }
        if (removeBond) BondHelper.forceUnbond(appContext, target)
    }

    private fun prepareClose(): BluetoothGatt? {
        initialized = false
        batteryNotificationsEnabled = false
        failPending(BleSessionException("Bluetooth session closed"))
        aa01Events.close()
        val client = gatt
        gatt = null
        return client
    }

    @SuppressLint("MissingPermission")
    private fun closeClient(client: BluetoothGatt?) {
        if (client != null) {
            try { client.close() } catch (_: Exception) {}
            BleRegistry.remove(client)
        }
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
        val batteryService = client.getService(Uuids.SERVICE)
        val batteryLevel = batteryService?.getCharacteristic(Uuids.LEVEL)
        val ossurService = client.getService(Uuids.OSSUR_SERVICE)
        val aa01 = ossurService?.getCharacteristic(Uuids.AA01)
        val aa02 = ossurService?.getCharacteristic(Uuids.AA02)
        val profile = FootGattProfilePresence(
            batteryService = batteryService != null,
            batteryLevel = batteryLevel != null,
            ossurService = ossurService != null,
            aa01 = aa01 != null,
            aa02 = aa02 != null
        )
        if (!isCompatibleFootProfile(profile)) {
            throw IncompatibleFootException("Required foot profile was not found")
        }
        batteryCharacteristic = requireNotNull(batteryLevel)
        aa01Characteristic = requireNotNull(aa01)
        aa02Characteristic = requireNotNull(aa02)
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
    private suspend fun writeAa01(
        value: ByteArray,
        onWriteStarted: () -> Unit = {}
    ) {
        if (!Aa01WriteAllowlist.isAllowed(value)) {
            throw BleSessionException("Proprietary command is not allowlisted")
        }
        val client = requireGatt()
        val characteristic = aa01Characteristic ?: throw BleSessionException("AA01 is unavailable")
        runGattOperation(
            type = OperationType.CHARACTERISTIC_WRITE,
            characteristicUuid = Uuids.AA01,
            onStarted = onWriteStarted
        ) {
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

    private fun standbyTransport(): StandbyTransactionTransport =
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

    private fun ankleTransport(): AnkleTransactionTransport =
        object : AnkleTransactionTransport {
            override suspend fun exchangeStandbyQuery(): StandbyCommandExchangeResult =
                exchangeStandby(StandbyProtocol.queryCommand(), StandbyResponseKind.QUERY)

            override suspend fun exchangeAnkle(
                command: ByteArray,
                expectedKind: AnkleResponseKind,
                onWriteAccepted: () -> Unit
            ): AnkleCommandExchangeResult = this@FootGattSession.exchangeAnkle(
                command,
                expectedKind,
                onWriteAccepted
            )
        }

    private fun discardQueuedAa01Events() {
        while (aa01Events.tryReceive().isSuccess) {
            // A new command may only consume packets observed after this transaction point.
        }
    }

    private suspend fun exchangeStandby(
        command: ByteArray,
        expectedKind: StandbyResponseKind,
        expectedState: StandbyState? = null
    ): StandbyCommandExchangeResult {
        discardQueuedAa01Events()
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
                    val event = aa01Events.receive()
                    if (event is Aa01Event.Standby && event.response.kind == expectedKind) {
                        observed = event.response
                        if (StandbyProtocol.matches(event.response, expectedKind, expectedState)) {
                            return@withTimeout event.response
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

    private suspend fun exchangeAnkle(
        command: ByteArray,
        expectedKind: AnkleResponseKind,
        onWriteAccepted: () -> Unit = {}
    ): AnkleCommandExchangeResult {
        discardQueuedAa01Events()
        try {
            writeAa01(command, onWriteStarted = onWriteAccepted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AnkleCommandExchangeResult.WriteFailed(
                e.userMessage("Bluetooth command write failed")
            )
        }

        return try {
            val response = withTimeout(COMMAND_RESPONSE_TIMEOUT_MS) {
                while (true) {
                    val event = aa01Events.receive()
                    if (event is Aa01Event.Ankle &&
                        AnkleProtocol.matches(event.response, expectedKind)
                    ) {
                        return@withTimeout event.response
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            AnkleCommandExchangeResult.Response(response)
        } catch (_: TimeoutCancellationException) {
            AnkleCommandExchangeResult.ResponseMissing("Ankle response timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AnkleCommandExchangeResult.ResponseMissing(e.userMessage("Ankle response failed"))
        }
    }

    private suspend fun awaitAutoEvent(timeoutMs: Long): AutoEventWaitResult = try {
        withTimeout(timeoutMs) {
            while (true) {
                when (val event = aa01Events.receive()) {
                    is Aa01Event.AutoActivity,
                    is Aa01Event.AutoCompletion,
                    is Aa01Event.Ankle -> return@withTimeout AutoEventWaitResult.Event(event)
                    is Aa01Event.Standby,
                    is Aa01Event.Unknown -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    } catch (_: TimeoutCancellationException) {
        AutoEventWaitResult.TimedOut
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AutoEventWaitResult.Failed(e.userMessage("Automatic alignment response failed"))
    }

    private fun requireResponse(result: StandbyCommandExchangeResult): StandbyResponse =
        when (result) {
            is StandbyCommandExchangeResult.Response -> result.response
            is StandbyCommandExchangeResult.WriteFailed -> throw BleSessionException(result.message)
            is StandbyCommandExchangeResult.ResponseMissing -> throw BleSessionException(result.message)
        }

    private fun requireAnkleResponse(result: AnkleCommandExchangeResult): AnkleResponse =
        when (result) {
            is AnkleCommandExchangeResult.Response -> result.response
            is AnkleCommandExchangeResult.WriteFailed -> throw BleSessionException(result.message)
            is AnkleCommandExchangeResult.ResponseMissing -> throw BleSessionException(result.message)
        }

    private suspend fun runGattOperation(
        type: OperationType,
        characteristicUuid: UUID,
        onStarted: () -> Unit = {},
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
            onStarted()

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
                if (newState == BluetoothProfile.STATE_DISCONNECTED) disconnected.complete(Unit)
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
            Uuids.AA01 -> aa01Events.trySend(Aa01Router.parse(value.copyOf()))
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

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}

fun StandbyState.displayName(): String = when (this) {
    StandbyState.ON -> "on"
    StandbyState.OFF -> "off"
    StandbyState.UNKNOWN -> "unknown"
}
