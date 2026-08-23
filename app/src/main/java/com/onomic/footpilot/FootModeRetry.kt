package com.onomic.footpilot

sealed interface FootModeMutationAttemptResult {
    data class Transaction(val read: FootModeTransactionRead) : FootModeMutationAttemptResult
    data class TransientFailure(val message: String) : FootModeMutationAttemptResult
    data class Rejected(val message: String) : FootModeMutationAttemptResult
    data object Busy : FootModeMutationAttemptResult

    fun isRetryable(): Boolean = when (this) {
        is Transaction -> !read.verified && read.failure?.retryable == true
        is TransientFailure -> true
        is Rejected,
        Busy -> false
    }
}

data class FootModeRetryRunResult(
    val finalAttempt: FootModeMutationAttemptResult?,
    val attempts: Int,
    val superseded: Boolean
)

/** Runs one immediate attempt and at most one delayed retry through the shared BLE countdown. */
internal class FootModeOneShotRetry(
    private val countdown: LiveRetryCountdown = LiveRetryCountdown(),
    private val retryLimit: Int = BleRetryPolicy.ONE_SHOT_CONTROL_RETRIES
) {
    init {
        require(retryLimit >= 0)
    }

    suspend fun run(
        stillCurrent: () -> Boolean,
        publishSecondsRemaining: (Int?) -> Unit,
        onRetryScheduled: (FootModeMutationAttemptResult) -> Unit,
        onRetryStarting: () -> Unit,
        attempt: suspend () -> FootModeMutationAttemptResult
    ): FootModeRetryRunResult {
        if (!stillCurrent()) return FootModeRetryRunResult(null, 0, superseded = true)
        var attempts = 1
        var result = attempt()
        var retries = 0
        while (result.isRetryable() && retries < retryLimit) {
            onRetryScheduled(result)
            val retry = countdown.awaitRetry(stillCurrent, publishSecondsRemaining)
            if (!retry || !stillCurrent()) {
                return FootModeRetryRunResult(result, attempts, superseded = true)
            }

            onRetryStarting()
            result = attempt()
            attempts++
            retries++
        }
        return FootModeRetryRunResult(result, attempts, superseded = false)
    }
}
