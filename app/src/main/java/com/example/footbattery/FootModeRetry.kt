package com.example.footbattery

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
    private val countdown: LiveRetryCountdown = LiveRetryCountdown()
) {
    suspend fun run(
        stillCurrent: () -> Boolean,
        publishSecondsRemaining: (Int?) -> Unit,
        onRetryScheduled: (FootModeMutationAttemptResult) -> Unit,
        onRetryStarting: () -> Unit,
        attempt: suspend () -> FootModeMutationAttemptResult
    ): FootModeRetryRunResult {
        if (!stillCurrent()) return FootModeRetryRunResult(null, 0, superseded = true)
        val first = attempt()
        if (!first.isRetryable()) {
            return FootModeRetryRunResult(first, 1, superseded = false)
        }

        onRetryScheduled(first)
        val retry = countdown.awaitRetry(stillCurrent, publishSecondsRemaining)
        if (!retry || !stillCurrent()) {
            return FootModeRetryRunResult(first, 1, superseded = true)
        }

        onRetryStarting()
        val second = attempt()
        return FootModeRetryRunResult(second, 2, superseded = false)
    }
}
