package com.example.footbattery

import java.util.EnumMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FootModeOperation {
    IDLE,
    CHECKING,
    SETTING,
    RETRY_WAIT
}

data class FootModeStatus(
    val lastVerified: FootModeValue? = null,
    val currentConfirmed: Boolean = false,
    val ambiguousAfterCommand: Boolean = false,
    val operation: FootModeOperation = FootModeOperation.IDLE,
    val requested: FootModeValue? = null,
    val retrySecondsRemaining: Int? = null,
    val message: String? = null
)

data class FootModesState(
    val targetAddress: String? = null,
    val chairExit: FootModeStatus = FootModeStatus(),
    val relax: FootModeStatus = FootModeStatus()
) {
    fun status(mode: FootMode): FootModeStatus = when (mode) {
        FootMode.CHAIR_EXIT -> chairExit
        FootMode.RELAX -> relax
    }

    fun withStatus(mode: FootMode, value: FootModeStatus): FootModesState = when (mode) {
        FootMode.CHAIR_EXIT -> copy(chairExit = value)
        FootMode.RELAX -> copy(relax = value)
    }
}

data class FootModeIntentToken(
    val targetAddress: String,
    val mode: FootMode,
    val requested: FootModeValue,
    val generation: Long
)

data class FootModeRowPresentation(
    val checked: Boolean,
    val enabled: Boolean,
    val secondaryText: String?,
    val stateDescription: String,
    val contentDescription: String
)

object FootModePresentation {
    fun create(
        mode: FootMode,
        status: FootModeStatus,
        hasSelectedFoot: Boolean,
        controlsAvailable: Boolean
    ): FootModeRowPresentation {
        val checked = status.lastVerified == FootModeValue.ON
        val secondary = when {
            !hasSelectedFoot -> "No foot selected"
            status.operation == FootModeOperation.CHECKING -> "Checking..."
            status.operation == FootModeOperation.RETRY_WAIT ->
                status.retrySecondsRemaining?.takeIf { it > 0 }
                    ?.let { "Retrying in ${it}s..." } ?: "Not confirmed"
            status.operation == FootModeOperation.SETTING -> status.message ?: when (status.requested) {
                FootModeValue.ON -> "Turning on..."
                FootModeValue.OFF -> "Turning off..."
                else -> "Updating..."
            }
            status.ambiguousAfterCommand -> "Not confirmed"
            status.message != null -> status.message
            !status.currentConfirmed -> "Not checked"
            else -> null
        }
        val stateDescription = when {
            status.operation == FootModeOperation.CHECKING -> "Checking"
            status.ambiguousAfterCommand ||
                status.operation == FootModeOperation.RETRY_WAIT ||
                status.operation == FootModeOperation.SETTING ->
                "Not confirmed"
            status.currentConfirmed && status.lastVerified == FootModeValue.ON -> "On"
            status.currentConfirmed && status.lastVerified == FootModeValue.OFF -> "Off"
            else -> "Not checked"
        }
        return FootModeRowPresentation(
            checked = checked,
            enabled = hasSelectedFoot && controlsAvailable && status.currentConfirmed &&
                status.operation == FootModeOperation.IDLE && !status.ambiguousAfterCommand,
            secondaryText = secondary,
            stateDescription = stateDescription,
            contentDescription = "${mode.displayName}, $stateDescription"
        )
    }
}

/** In-memory, selected-foot-scoped mode truth and generation-safe mutation intent. */
class FootModeStateStore(initial: FootModesState = FootModesState()) {
    private val guard = Any()
    private val generations = EnumMap<FootMode, Long>(FootMode::class.java).apply {
        FootMode.entries.forEach { put(it, 0L) }
    }
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<FootModesState> = _state.asStateFlow()

    fun syncTarget(targetAddress: String?) = synchronized(guard) {
        if (_state.value.targetAddress != targetAddress) resetTargetLocked(targetAddress)
    }

    fun resetTarget(targetAddress: String?) = synchronized(guard) {
        resetTargetLocked(targetAddress)
    }

    fun beginRefresh(targetAddress: String): Boolean = synchronized(guard) {
        if (_state.value.targetAddress != targetAddress) resetTargetLocked(targetAddress)
        if (FootMode.entries.any {
                _state.value.status(it).operation in setOf(
                    FootModeOperation.SETTING,
                    FootModeOperation.RETRY_WAIT
                )
            }
        ) {
            return@synchronized false
        }
        var next = _state.value
        FootMode.entries.forEach { mode ->
            next = next.withStatus(
                mode,
                next.status(mode).copy(
                    currentConfirmed = false,
                    operation = FootModeOperation.CHECKING,
                    requested = null,
                    retrySecondsRemaining = null,
                    message = "Checking..."
                )
            )
        }
        _state.value = next
        true
    }

    fun applyQuery(targetAddress: String, read: FootModeQueryRead) = synchronized(guard) {
        if (_state.value.targetAddress != targetAddress) return@synchronized
        val previous = _state.value.status(read.mode)
        val updated = if (read.value != null) {
            FootModeStatus(
                lastVerified = read.value,
                currentConfirmed = true
            )
        } else {
            previous.copy(
                currentConfirmed = false,
                operation = FootModeOperation.IDLE,
                requested = null,
                retrySecondsRemaining = null,
                message = read.error ?: "Not checked"
            )
        }
        _state.value = _state.value.withStatus(read.mode, updated)
    }

    fun failRefresh(targetAddress: String, message: String) = synchronized(guard) {
        if (_state.value.targetAddress != targetAddress) return@synchronized
        var next = _state.value
        FootMode.entries.forEach { mode ->
            val previous = next.status(mode)
            if (previous.operation == FootModeOperation.CHECKING) {
                next = next.withStatus(
                    mode,
                    previous.copy(
                        currentConfirmed = false,
                        operation = FootModeOperation.IDLE,
                        message = message
                    )
                )
            }
        }
        _state.value = next
    }

    fun beginIntent(
        targetAddress: String,
        mode: FootMode,
        requested: FootModeValue
    ): FootModeIntentToken = synchronized(guard) {
        require(requested != FootModeValue.UNKNOWN)
        if (_state.value.targetAddress != targetAddress) resetTargetLocked(targetAddress)
        val generation = requireNotNull(generations[mode]) + 1L
        generations[mode] = generation
        val token = FootModeIntentToken(targetAddress, mode, requested, generation)
        val previous = _state.value.status(mode)
        _state.value = _state.value.withStatus(
            mode,
            previous.copy(
                operation = FootModeOperation.SETTING,
                requested = requested,
                retrySecondsRemaining = null,
                message = if (requested == FootModeValue.ON) "Turning on..." else "Turning off..."
            )
        )
        token
    }

    fun isCurrent(token: FootModeIntentToken): Boolean = synchronized(guard) {
        _state.value.targetAddress == token.targetAddress && generations[token.mode] == token.generation
    }

    fun applyMutation(token: FootModeIntentToken, read: FootModeTransactionRead) =
        synchronized(guard) {
            if (!isCurrentLocked(token)) return@synchronized
            val previous = _state.value.status(token.mode)
            val hasFreshTruth = read.finalValue != null
            _state.value = _state.value.withStatus(
                token.mode,
                previous.copy(
                    lastVerified = read.finalValue ?: previous.lastVerified,
                    currentConfirmed = hasFreshTruth,
                    ambiguousAfterCommand = if (hasFreshTruth) {
                        false
                    } else {
                        previous.ambiguousAfterCommand || read.ambiguous
                    },
                    operation = FootModeOperation.IDLE,
                    requested = null,
                    retrySecondsRemaining = null,
                    message = if (read.verified) null else read.error
                )
            )
        }

    fun applyTransientFailure(token: FootModeIntentToken, message: String) = synchronized(guard) {
        if (!isCurrentLocked(token)) return@synchronized
        val previous = _state.value.status(token.mode)
        _state.value = _state.value.withStatus(
            token.mode,
            previous.copy(
                currentConfirmed = false,
                operation = FootModeOperation.IDLE,
                requested = null,
                retrySecondsRemaining = null,
                message = message
            )
        )
    }

    /** A rejected attempt never contacted the foot, so any fresh truth remains valid. */
    fun applyRejectedIntent(token: FootModeIntentToken, message: String) = synchronized(guard) {
        if (!isCurrentLocked(token)) return@synchronized
        val previous = _state.value.status(token.mode)
        _state.value = _state.value.withStatus(
            token.mode,
            previous.copy(
                operation = FootModeOperation.IDLE,
                requested = null,
                retrySecondsRemaining = null,
                message = message
            )
        )
    }

    fun beginRetry(token: FootModeIntentToken) = synchronized(guard) {
        if (!isCurrentLocked(token)) return@synchronized
        val previous = _state.value.status(token.mode)
        _state.value = _state.value.withStatus(
            token.mode,
            previous.copy(
                operation = FootModeOperation.RETRY_WAIT,
                requested = token.requested,
                retrySecondsRemaining = null,
                message = "Not confirmed"
            )
        )
    }

    fun updateRetrySeconds(token: FootModeIntentToken, seconds: Int?) = synchronized(guard) {
        if (!isCurrentLocked(token)) return@synchronized
        val previous = _state.value.status(token.mode)
        if (previous.operation != FootModeOperation.RETRY_WAIT) return@synchronized
        _state.value = _state.value.withStatus(
            token.mode,
            previous.copy(retrySecondsRemaining = seconds)
        )
    }

    fun beginRetryAttempt(token: FootModeIntentToken) = synchronized(guard) {
        if (!isCurrentLocked(token)) return@synchronized
        val previous = _state.value.status(token.mode)
        _state.value = _state.value.withStatus(
            token.mode,
            previous.copy(
                operation = FootModeOperation.SETTING,
                requested = token.requested,
                retrySecondsRemaining = null,
                message = if (token.requested == FootModeValue.ON) {
                    "Turning on..."
                } else {
                    "Turning off..."
                }
            )
        )
    }

    private fun isCurrentLocked(token: FootModeIntentToken): Boolean =
        _state.value.targetAddress == token.targetAddress && generations[token.mode] == token.generation

    private fun resetTargetLocked(targetAddress: String?) {
        FootMode.entries.forEach { mode ->
            generations[mode] = requireNotNull(generations[mode]) + 1L
        }
        _state.value = FootModesState(targetAddress = targetAddress)
    }
}

object FootModeRepo {
    private val store = FootModeStateStore()
    val state: StateFlow<FootModesState> = store.state

    fun syncTarget(targetAddress: String?) = store.syncTarget(targetAddress)
    fun resetForFootChange(targetAddress: String?) = store.resetTarget(targetAddress)
    fun beginRefresh(targetAddress: String): Boolean = store.beginRefresh(targetAddress)
    fun applyQuery(targetAddress: String, read: FootModeQueryRead) = store.applyQuery(targetAddress, read)
    fun failRefresh(targetAddress: String, message: String) = store.failRefresh(targetAddress, message)
    fun beginIntent(targetAddress: String, mode: FootMode, requested: FootModeValue) =
        store.beginIntent(targetAddress, mode, requested)
    fun isCurrent(token: FootModeIntentToken): Boolean = store.isCurrent(token)
    fun applyMutation(token: FootModeIntentToken, read: FootModeTransactionRead) =
        store.applyMutation(token, read)
    fun applyTransientFailure(token: FootModeIntentToken, message: String) =
        store.applyTransientFailure(token, message)
    fun applyRejectedIntent(token: FootModeIntentToken, message: String) =
        store.applyRejectedIntent(token, message)
    fun beginRetry(token: FootModeIntentToken) = store.beginRetry(token)
    fun updateRetrySeconds(token: FootModeIntentToken, seconds: Int?) =
        store.updateRetrySeconds(token, seconds)
    fun beginRetryAttempt(token: FootModeIntentToken) = store.beginRetryAttempt(token)
}
