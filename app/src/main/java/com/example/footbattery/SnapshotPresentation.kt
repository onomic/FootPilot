package com.example.footbattery

data class SnapshotDisplayState(
    val batteryLevel: Int?,
    val standby: StandbyState,
    val lastChecked: Long,
    val checkedLabel: String,
    val verificationMessage: String?,
    val standbyAmbiguousAfterCommand: Boolean
) {
    val batteryLine: String
        get() = batteryLevel?.let { "Battery $it%" } ?: "Battery —"

    val standbyLine: String
        get() = when {
            standbyAmbiguousAfterCommand -> "Standby not confirmed"
            standby == StandbyState.ON -> "Standby on"
            standby == StandbyState.OFF -> "Standby off"
            else -> "Standby not checked"
        }

    /** Requested state for the safe confirmation-driven action, or null when no action is safe. */
    val standbyAction: StandbyState?
        get() = when (standby) {
            StandbyState.ON -> StandbyState.OFF
            StandbyState.OFF -> StandbyState.ON
            StandbyState.UNKNOWN -> null
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
        val ambiguous = snapshot.completeness ==
            SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
        return SnapshotDisplayState(
            batteryLevel = liveBatteryLevel?.takeIf { it in 0..100 } ?: snapshot.batteryLevel,
            standby = if (ambiguous) StandbyState.UNKNOWN else snapshot.standby,
            lastChecked = snapshot.lastChecked,
            checkedLabel = if (pending || ambiguous) "Last complete check" else "Last checked",
            verificationMessage = when {
                ambiguous -> "State could not be verified after command"
                pending -> "Battery not verified after standby change"
                else -> null
            },
            standbyAmbiguousAfterCommand = ambiguous
        )
    }
}

fun disconnectStandbyWarning(snapshot: SnapshotState): String? = when {
    snapshot.completeness == SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND ->
        "Standby could not be confirmed and may remain on after disconnecting."
    snapshot.standby == StandbyState.ON -> "Standby will remain on after disconnecting."
    else -> null
}
