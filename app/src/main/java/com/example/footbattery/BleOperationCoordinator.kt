package com.example.footbattery

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

enum class BleOperationKind(val statusText: String) {
    SCHEDULED_CHECK("Checking..."),
    MANUAL_CHECK("Checking..."),
    NOTIFICATION_CHECK("Checking..."),
    LIVE_CONNECT("Checking..."),
    LIVE_REFRESH("Checking..."),
    STANDBY_ON("Turning standby on..."),
    STANDBY_OFF("Turning standby off..."),
    STANDBY_TOGGLE("Updating standby..."),
    ANKLE_ADJUST("Adjusting ankle..."),
    PRESET_APPLY("Applying preset..."),
    AUTO_ALIGN("Automatic alignment"),
    DISCONNECT("Disconnecting...")
}

data class BleCoordinationState(
    val active: BleOperationKind? = null,
    /** Historical name retained for compatibility; now reserves every user device-control action. */
    val standbyPending: BleOperationKind? = null
) {
    val isBusy: Boolean get() = active != null || standbyPending != null
    val visibleOperation: BleOperationKind? get() = standbyPending ?: active
}

sealed interface CoordinatedResult<out T> {
    data class Completed<T>(val value: T) : CoordinatedResult<T>
    data object Busy : CoordinatedResult<Nothing>
}

/**
 * Process-wide ownership gate for every connection and GATT transaction.
 *
 * Ordinary/manual checks fail fast when another owner exists. A standby request reserves
 * priority, waits for an already-running transaction to finish safely, and rejects duplicate
 * standby requests while it is pending or active. Scheduled work therefore skips rather than
 * delaying a user operation.
 */
class OperationCoordinator {
    private val mutex = Mutex()
    private val deviceControlReserved = AtomicBoolean(false)
    private val _state = MutableStateFlow(BleCoordinationState())
    val state: StateFlow<BleCoordinationState> = _state.asStateFlow()

    fun isBusy(): Boolean = _state.value.isBusy

    suspend fun <T> tryRun(
        kind: BleOperationKind,
        block: suspend () -> T
    ): CoordinatedResult<T> {
        if (deviceControlReserved.get() || !mutex.tryLock()) return CoordinatedResult.Busy
        if (deviceControlReserved.get()) {
            mutex.unlock()
            return CoordinatedResult.Busy
        }
        return runLocked(kind, block)
    }

    suspend fun <T> runQueued(
        kind: BleOperationKind,
        block: suspend () -> T
    ): CoordinatedResult<T> {
        mutex.lock()
        return runLocked(kind, block)
    }

    suspend fun <T> runStandby(
        kind: BleOperationKind,
        block: suspend () -> T
    ): CoordinatedResult<T> {
        require(kind == BleOperationKind.STANDBY_ON || kind == BleOperationKind.STANDBY_OFF)
        return runDeviceControl(kind, block)
    }

    suspend fun <T> runDeviceControl(
        kind: BleOperationKind,
        block: suspend () -> T
    ): CoordinatedResult<T> {
        require(kind in DEVICE_CONTROL_KINDS)
        if (!deviceControlReserved.compareAndSet(false, true)) return CoordinatedResult.Busy

        _state.update { it.copy(standbyPending = kind) }
        var acquired = false
        try {
            mutex.lock()
            acquired = true
            _state.update { it.copy(active = kind, standbyPending = null) }
            return CoordinatedResult.Completed(block())
        } finally {
            if (acquired) mutex.unlock()
            deviceControlReserved.set(false)
            _state.update { current ->
                current.copy(
                    active = if (current.active == kind) null else current.active,
                    standbyPending = if (current.standbyPending == kind) null else current.standbyPending
                )
            }
        }
    }

    private companion object {
        val DEVICE_CONTROL_KINDS = setOf(
            BleOperationKind.STANDBY_ON,
            BleOperationKind.STANDBY_OFF,
            BleOperationKind.STANDBY_TOGGLE,
            BleOperationKind.ANKLE_ADJUST,
            BleOperationKind.PRESET_APPLY,
            BleOperationKind.AUTO_ALIGN
        )
    }

    private suspend fun <T> runLocked(
        kind: BleOperationKind,
        block: suspend () -> T
    ): CoordinatedResult<T> {
        _state.update { it.copy(active = kind) }
        return try {
            CoordinatedResult.Completed(block())
        } finally {
            _state.update { current ->
                current.copy(active = if (current.active == kind) null else current.active)
            }
            mutex.unlock()
        }
    }
}

object BleOperationCoordinator {
    private val delegate = OperationCoordinator()
    val state: StateFlow<BleCoordinationState> = delegate.state

    fun isBusy(): Boolean = delegate.isBusy()

    suspend fun <T> tryRun(kind: BleOperationKind, block: suspend () -> T): CoordinatedResult<T> =
        delegate.tryRun(kind, block)

    suspend fun <T> runQueued(kind: BleOperationKind, block: suspend () -> T): CoordinatedResult<T> =
        delegate.runQueued(kind, block)

    suspend fun <T> runStandby(kind: BleOperationKind, block: suspend () -> T): CoordinatedResult<T> =
        delegate.runStandby(kind, block)

    suspend fun <T> runDeviceControl(
        kind: BleOperationKind,
        block: suspend () -> T
    ): CoordinatedResult<T> = delegate.runDeviceControl(kind, block)
}
