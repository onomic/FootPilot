package com.onomic.footpilot

data class LowBatteryAlertDecision(
    val armed: Boolean,
    val shouldAlert: Boolean
)

/** Pure low-battery arming transition shared by production code and local tests. */
object LowBatteryAlertReducer {
    fun reduce(wasArmed: Boolean, batteryLevel: Int, threshold: Int): LowBatteryAlertDecision {
        require(batteryLevel in 0..100)
        return when {
            batteryLevel < threshold && wasArmed -> LowBatteryAlertDecision(
                armed = false,
                shouldAlert = true
            )
            batteryLevel >= threshold -> LowBatteryAlertDecision(
                armed = true,
                shouldAlert = false
            )
            else -> LowBatteryAlertDecision(armed = false, shouldAlert = false)
        }
    }
}

/** Applies one valid reading to both live display and safety evaluation exactly once. */
object FreshBatteryResultHandler {
    fun handle(
        batteryLevel: Int?,
        updateLiveLevel: (Int) -> Unit,
        evaluateLowBattery: (Int) -> Unit
    ): Boolean {
        val validLevel = batteryLevel?.takeIf { it in 0..100 } ?: return false
        updateLiveLevel(validLevel)
        evaluateLowBattery(validLevel)
        return true
    }
}
