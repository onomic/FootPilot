package com.onomic.footpilot

sealed interface StandbyAttemptRequest {
    data class Absolute(val requested: StandbyState) : StandbyAttemptRequest {
        init {
            require(requested != StandbyState.UNKNOWN)
        }
    }

    data object Toggle : StandbyAttemptRequest
}

sealed interface StandbyAttemptResult {
    data class Transaction(val read: StandbyTransactionRead) : StandbyAttemptResult
    data class TransientFailure(val message: String) : StandbyAttemptResult
    data class Rejected(val message: String) : StandbyAttemptResult
    data object Busy : StandbyAttemptResult

    fun isRetryable(): Boolean = when (this) {
        is Transaction -> !read.verified && read.failure?.retryable == true
        is TransientFailure -> true
        is Rejected,
        Busy -> false
    }

    fun resolvedTarget(): StandbyState? = (this as? Transaction)
        ?.read
        ?.requested
        ?.takeIf { it != StandbyState.UNKNOWN }
}

data class StandbyRequestToken(
    val targetAddress: String,
    val initialRequest: StandbyAttemptRequest,
    val generation: Long
)

/** Process-owned generation used to invalidate an older delayed control intent. */
internal class StandbyRequestGeneration {
    private val guard = Any()
    private var generation = 0L

    fun begin(
        targetAddress: String,
        request: StandbyAttemptRequest
    ): StandbyRequestToken = synchronized(guard) {
        StandbyRequestToken(targetAddress, request, ++generation)
    }

    fun isCurrent(token: StandbyRequestToken): Boolean = synchronized(guard) {
        token.generation == generation
    }

    fun invalidate() = synchronized(guard) {
        generation++
    }
}

data class StandbyRetryRunResult(
    val finalAttempt: StandbyAttemptResult?,
    val attempts: Int,
    val superseded: Boolean,
    val finalRequest: StandbyAttemptRequest
)

/** Runs one immediate Standby attempt and only the bounded retries authorized by policy. */
internal class StandbyOneShotRetry(
    private val countdown: LiveRetryCountdown = LiveRetryCountdown(),
    private val retryLimit: Int = BleRetryPolicy.ONE_SHOT_CONTROL_RETRIES
) {
    init {
        require(retryLimit >= 0)
    }

    suspend fun run(
        initialRequest: StandbyAttemptRequest,
        stillCurrent: () -> Boolean,
        publishSecondsRemaining: (Int?) -> Unit,
        onRetryScheduled: (StandbyAttemptResult, StandbyAttemptRequest) -> Unit,
        onRetryStarting: (StandbyAttemptRequest) -> Unit,
        attempt: suspend (StandbyAttemptRequest) -> StandbyAttemptResult
    ): StandbyRetryRunResult {
        var request = initialRequest
        if (!stillCurrent()) {
            return StandbyRetryRunResult(null, 0, superseded = true, finalRequest = request)
        }

        var attempts = 1
        var result = attempt(request)
        if (!stillCurrent()) {
            return StandbyRetryRunResult(result, attempts, superseded = true, finalRequest = request)
        }

        var retries = 0
        while (result.isRetryable() && retries < retryLimit) {
            result.resolvedTarget()?.let { request = StandbyAttemptRequest.Absolute(it) }
            onRetryScheduled(result, request)
            if (!countdown.awaitRetry(stillCurrent, publishSecondsRemaining) || !stillCurrent()) {
                return StandbyRetryRunResult(
                    result,
                    attempts,
                    superseded = true,
                    finalRequest = request
                )
            }

            onRetryStarting(request)
            result = attempt(request)
            attempts++
            retries++
            if (!stillCurrent()) {
                return StandbyRetryRunResult(
                    result,
                    attempts,
                    superseded = true,
                    finalRequest = request
                )
            }
        }

        return StandbyRetryRunResult(
            result,
            attempts,
            superseded = false,
            finalRequest = request
        )
    }
}
