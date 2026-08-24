package com.onomic.footpilot

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay

/** Policy for the silent delay between separate GATT sessions for one foot. */
object BleInterOperationPolicy {
    const val QUIET_PERIOD_MS = 4_000L
}

/**
 * Process-local, target-scoped release timing. This is timing state only;
 * [BleOperationCoordinator] remains the sole BLE transaction owner.
 */
internal class TargetScopedBleInterOperationCooldown(
    private val clock: () -> Long,
    private val sleeper: suspend (Long) -> Unit,
    private val quietPeriodMs: Long = BleInterOperationPolicy.QUIET_PERIOD_MS
) {
    private val guard = Any()
    private val releaseCompletedAt = mutableMapOf<String, Long>()

    init {
        require(quietPeriodMs >= 0L)
    }

    fun recordReleaseCompleted(address: String) {
        val normalized = normalizeTargetAddress(address)
        val completedAt = clock()
        synchronized(guard) {
            releaseCompletedAt[normalized] = completedAt
        }
    }

    fun remainingMs(address: String): Long = synchronized(guard) {
        remainingQuietPeriodMs(
            releaseCompletedAt = releaseCompletedAt[normalizeTargetAddress(address)],
            nowMs = clock(),
            quietPeriodMs = quietPeriodMs
        )
    }

    fun isReady(address: String): Boolean = remainingMs(address) == 0L

    suspend fun awaitReady(address: String) {
        while (true) {
            val remaining = remainingMs(address)
            if (remaining == 0L) return
            sleeper(remaining)
        }
    }
}

internal fun remainingQuietPeriodMs(
    releaseCompletedAt: Long?,
    nowMs: Long,
    quietPeriodMs: Long = BleInterOperationPolicy.QUIET_PERIOD_MS
): Long {
    require(quietPeriodMs >= 0L)
    if (releaseCompletedAt == null || quietPeriodMs == 0L) return 0L
    if (nowMs <= releaseCompletedAt) return quietPeriodMs

    val elapsed = nowMs - releaseCompletedAt
    return if (elapsed >= quietPeriodMs) 0L else quietPeriodMs - elapsed
}

/** Shared production gate used immediately before a new GATT session is opened. */
object BleInterOperationCooldown {
    private const val TAG = "FootPilotBle"
    private val tracker = TargetScopedBleInterOperationCooldown(
        clock = SystemClock::elapsedRealtime,
        sleeper = { remainingMs -> delay(remainingMs) }
    )

    fun recordReleaseCompleted(address: String) {
        tracker.recordReleaseCompleted(address)
        debug("INTER_OP_COOLDOWN release-complete target=${normalizeTargetAddress(address)}")
    }

    fun remainingMs(address: String): Long = tracker.remainingMs(address)

    fun isReady(address: String): Boolean = tracker.isReady(address)

    suspend fun awaitReady(address: String) {
        val normalized = normalizeTargetAddress(address)
        val remaining = tracker.remainingMs(normalized)
        if (remaining > 0L) {
            debug("INTER_OP_COOLDOWN target=$normalized remainingMs=$remaining")
        }
        tracker.awaitReady(normalized)
        debug("INTER_OP_COOLDOWN ready target=$normalized")
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
