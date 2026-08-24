package com.onomic.footpilot

sealed interface FootModeRefreshAttemptResult {
    data object Success : FootModeRefreshAttemptResult
    data class TransientFailure(val message: String) : FootModeRefreshAttemptResult
    data class Rejected(val message: String) : FootModeRefreshAttemptResult
    data object Busy : FootModeRefreshAttemptResult

    fun isRetryable(): Boolean = this is TransientFailure
}

data class FootModeRefreshRetryRunResult(
    val finalAttempt: FootModeRefreshAttemptResult?,
    val attempts: Int,
    val superseded: Boolean
)

internal fun FootModesRefreshRead.toAttemptResult(): FootModeRefreshAttemptResult {
    for (mode in FootModeRefresh.queryOrder) {
        val read = results[mode] ?: return FootModeRefreshAttemptResult.TransientFailure(
            "${mode.displayName} was not checked"
        )
        if (read.value == null) {
            return FootModeRefreshAttemptResult.TransientFailure(
                read.error ?: "Foot mode check failed"
            )
        }
    }
    return FootModeRefreshAttemptResult.Success
}

/** Runs one immediate read-only refresh and at most one retry through the shared BLE countdown. */
internal class FootModeRefreshOneShotRetry(
    private val countdown: LiveRetryCountdown = LiveRetryCountdown(),
    private val retryLimit: Int = BleRetryPolicy.ONE_SHOT_CONTROL_RETRIES
) {
    init {
        require(retryLimit >= 0)
    }

    suspend fun run(
        stillCurrent: () -> Boolean,
        publishSecondsRemaining: (Int?) -> Unit,
        onRetryScheduled: (FootModeRefreshAttemptResult.TransientFailure) -> Unit,
        onRetryStarting: () -> Unit,
        attempt: suspend () -> FootModeRefreshAttemptResult
    ): FootModeRefreshRetryRunResult {
        if (!stillCurrent()) {
            return FootModeRefreshRetryRunResult(null, 0, superseded = true)
        }

        var attempts = 1
        var result = attempt()
        var retries = 0
        while (result.isRetryable() && retries < retryLimit) {
            onRetryScheduled(result as FootModeRefreshAttemptResult.TransientFailure)
            val retry = countdown.awaitRetry(stillCurrent, publishSecondsRemaining)
            if (!retry || !stillCurrent()) {
                return FootModeRefreshRetryRunResult(result, attempts, superseded = true)
            }

            onRetryStarting()
            result = attempt()
            attempts++
            retries++
        }
        return FootModeRefreshRetryRunResult(result, attempts, superseded = false)
    }
}
