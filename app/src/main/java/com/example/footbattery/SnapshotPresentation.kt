package com.example.footbattery

data class SnapshotDisplayState(
    val batteryLevel: Int?,
    val standby: StandbyState,
    val lastChecked: Long,
    val checkedLabel: String,
    val verificationMessage: String?
) {
    val batteryLine: String
        get() = batteryLevel?.let { "Battery $it%" } ?: "Battery —"

    val standbyLine: String
        get() = when (standby) {
            StandbyState.ON -> "Standby on"
            StandbyState.OFF -> "Standby off"
            StandbyState.UNKNOWN -> "Standby not checked"
        }

    fun checkedLine(formattedTime: String?): String = when {
        lastChecked > 0L && formattedTime != null -> "$checkedLabel: $formattedTime"
        verificationMessage != null -> "No complete check yet"
        else -> "State not checked yet"
    }
}

/** Pure presentation model: only the battery may come from newer live process state. */
object SnapshotPresentation {
    fun create(snapshot: SnapshotState, liveBatteryLevel: Int? = null): SnapshotDisplayState {
        val pending = snapshot.completeness ==
            SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
        return SnapshotDisplayState(
            batteryLevel = liveBatteryLevel?.takeIf { it in 0..100 } ?: snapshot.batteryLevel,
            standby = snapshot.standby,
            lastChecked = snapshot.lastChecked,
            checkedLabel = if (pending) "Last complete check" else "Last checked",
            verificationMessage = if (pending) {
                "Battery not verified after standby change"
            } else {
                null
            }
        )
    }
}
