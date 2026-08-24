package com.onomic.footpilot

import kotlinx.coroutines.flow.first

/** Silently defers only the automatic read-only mode refresh until coordinator admission. */
internal class FootModeRefreshCoordinatorDefer(
    private val tryAcquire: suspend (
        block: suspend () -> FootModeRefreshAttemptResult
    ) -> CoordinatedResult<FootModeRefreshAttemptResult> = { block ->
        BleOperationCoordinator.tryRun(BleOperationKind.FOOT_MODES_REFRESH, block)
    },
    private val awaitAvailable: suspend () -> Unit = {
        BleOperationCoordinator.state.first { !it.isBusy }
    }
) {
    suspend fun run(
        stillCurrent: () -> Boolean,
        admittedAttempt: suspend () -> FootModeRefreshAttemptResult
    ): FootModeRefreshAttemptResult {
        while (true) {
            if (!stillCurrent()) return selectedFootChanged()

            when (val coordinated = tryAcquire {
                if (stillCurrent()) admittedAttempt() else selectedFootChanged()
            }) {
                is CoordinatedResult.Completed -> return coordinated.value
                CoordinatedResult.Busy -> {
                    if (!stillCurrent()) return selectedFootChanged()
                    awaitAvailable()
                    if (!stillCurrent()) return selectedFootChanged()
                }
            }
        }
    }

    private fun selectedFootChanged() =
        FootModeRefreshAttemptResult.Rejected("Selected foot changed")
}
