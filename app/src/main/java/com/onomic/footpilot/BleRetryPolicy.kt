package com.onomic.footpilot

/** One code-level policy for delays between deliberate automatic BLE retry attempts. */
object BleRetryPolicy {
    const val RETRY_DELAY_MS = 10_000L
    const val ONE_SHOT_CONTROL_RETRIES = 1

    val retryDelaySeconds: Int
        get() = (RETRY_DELAY_MS / 1_000L).toInt()
}
