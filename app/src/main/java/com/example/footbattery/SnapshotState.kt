package com.example.footbattery

enum class StandbyState {
    UNKNOWN,
    OFF,
    ON;

    companion object {
        fun fromPersisted(value: String?): StandbyState =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class SnapshotCompleteness {
    COMPLETE,
    STANDBY_CONFIRMED_BATTERY_PENDING,
    STANDBY_STATE_UNKNOWN_AFTER_COMMAND;

    companion object {
        /** Standby v1 snapshots written before this field existed were complete snapshots. */
        fun fromPersisted(value: String?): SnapshotCompleteness =
            entries.firstOrNull { it.name == value } ?: COMPLETE
    }
}

data class SnapshotState(
    val batteryLevel: Int? = null,
    val standby: StandbyState = StandbyState.UNKNOWN,
    val lastChecked: Long = 0L,
    val completeness: SnapshotCompleteness = SnapshotCompleteness.COMPLETE
)

sealed interface SnapshotEvent {
    data class NormalCheck(
        val batteryLevel: Int?,
        val standby: StandbyState?,
        val checkedAt: Long
    ) : SnapshotEvent

    data class StandbyChange(
        val requested: StandbyState,
        val finalState: StandbyState?,
        val verified: Boolean,
        val batteryLevel: Int?,
        val checkedAt: Long,
        val ambiguous: Boolean = false
    ) : SnapshotEvent
}

data class SnapshotReduction(
    val snapshot: SnapshotState,
    val completeSnapshotSaved: Boolean,
    val standbyChangeConfirmed: Boolean,
    /** Every valid battery read is surfaced even when the complete snapshot is rejected. */
    val freshBatteryLevel: Int?
)

/** Pure state transition rules used by both production persistence and local unit tests. */
object SnapshotReducer {
    fun reduce(previous: SnapshotState, event: SnapshotEvent): SnapshotReduction = when (event) {
        is SnapshotEvent.NormalCheck -> {
            val standby = event.standby
            val battery = event.batteryLevel?.takeIf { it in 0..100 }
            if (battery != null && standby != null && standby != StandbyState.UNKNOWN) {
                SnapshotReduction(
                    snapshot = SnapshotState(
                        battery,
                        standby,
                        event.checkedAt,
                        SnapshotCompleteness.COMPLETE
                    ),
                    completeSnapshotSaved = true,
                    standbyChangeConfirmed = false,
                    freshBatteryLevel = battery
                )
            } else {
                SnapshotReduction(
                    previous,
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = false,
                    freshBatteryLevel = battery
                )
            }
        }

        is SnapshotEvent.StandbyChange -> {
            val battery = event.batteryLevel?.takeIf { it in 0..100 }
            val finalState = event.finalState?.takeIf { it != StandbyState.UNKNOWN }
            val confirmed = event.verified && finalState == event.requested
            when {
                event.ambiguous -> SnapshotReduction(
                    snapshot = previous.copy(
                        standby = StandbyState.UNKNOWN,
                        completeness = SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
                    ),
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = false,
                    freshBatteryLevel = battery
                )

                finalState != null && battery != null -> SnapshotReduction(
                    snapshot = SnapshotState(
                        battery,
                        finalState,
                        event.checkedAt,
                        SnapshotCompleteness.COMPLETE
                    ),
                    completeSnapshotSaved = true,
                    standbyChangeConfirmed = confirmed,
                    freshBatteryLevel = battery
                )

                finalState != null -> SnapshotReduction(
                    snapshot = previous.copy(
                        standby = finalState,
                        completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
                    ),
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = confirmed,
                    freshBatteryLevel = battery
                )

                else -> SnapshotReduction(
                    previous,
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = false,
                    freshBatteryLevel = battery
                )
            }
        }
    }
}
