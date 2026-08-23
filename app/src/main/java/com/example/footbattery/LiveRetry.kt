package com.example.footbattery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/** One-second, cancellation-safe presentation timer for a bounded BLE retry wait. */
internal class LiveRetryCountdown(
    private val totalSeconds: Int = BleRetryPolicy.retryDelaySeconds,
    private val awaitTick: suspend () -> Unit = { delay(1_000L) }
) {
    init {
        require(totalSeconds > 0)
    }

    suspend fun awaitRetry(
        stillRequested: () -> Boolean,
        publishSecondsRemaining: (Int?) -> Unit
    ): Boolean {
        return try {
            for (seconds in totalSeconds downTo 1) {
                if (!stillRequested()) return false
                publishSecondsRemaining(seconds)
                awaitTick()
            }
            stillRequested()
        } finally {
            publishSecondsRemaining(null)
        }
    }
}

/** A callback-safe, one-shot handoff from the GATT callback to the persistent owner coroutine. */
internal class LiveDisconnectSignal {
    private val disconnected = CompletableDeferred<Unit>()

    fun signal(): Boolean = disconnected.complete(Unit)

    suspend fun await() {
        disconnected.await()
    }
}

internal sealed interface LiveConnectionAttemptResult {
    data object Busy : LiveConnectionAttemptResult
    data object Stopped : LiveConnectionAttemptResult
    data object Failed : LiveConnectionAttemptResult
    data class Ready(
        val awaitUnexpectedDisconnect: suspend () -> Unit
    ) : LiveConnectionAttemptResult
}

/**
 * Sequential persistent owner: the first attempt is immediate, while only failures and an
 * unexpected loss after READY pass through [awaitRetry].
 */
internal suspend fun runPersistentLiveConnection(
    stillRequested: () -> Boolean,
    attempt: suspend () -> LiveConnectionAttemptResult,
    awaitRetry: suspend () -> Boolean,
    coordinatorYield: suspend () -> Unit = { delay(500L) }
) {
    var retryRequired = false
    while (stillRequested()) {
        if (retryRequired) {
            if (!awaitRetry()) return
            retryRequired = false
        }

        when (val result = attempt()) {
            LiveConnectionAttemptResult.Busy -> coordinatorYield()
            LiveConnectionAttemptResult.Stopped -> return
            LiveConnectionAttemptResult.Failed -> retryRequired = true
            is LiveConnectionAttemptResult.Ready -> {
                result.awaitUnexpectedDisconnect()
                if (!stillRequested()) return
                retryRequired = true
            }
        }
    }
}
