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
    STANDBY_CONFIRMED_BATTERY_PENDING;

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
            val confirmed = event.verified && event.finalState == event.requested
            val battery = event.batteryLevel?.takeIf { it in 0..100 }
            when {
                confirmed && battery != null -> SnapshotReduction(
                    snapshot = SnapshotState(
                        battery,
                        event.requested,
                        event.checkedAt,
                        SnapshotCompleteness.COMPLETE
                    ),
                    completeSnapshotSaved = true,
                    standbyChangeConfirmed = true,
                    freshBatteryLevel = battery
                )

                confirmed -> SnapshotReduction(
                    snapshot = previous.copy(
                        standby = event.requested,
                        completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
                    ),
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = true,
                    freshBatteryLevel = battery
                )

                event.finalState != null &&
                    event.finalState != StandbyState.UNKNOWN &&
                    event.finalState != previous.standby -> SnapshotReduction(
                    snapshot = previous.copy(
                        standby = event.finalState,
                        completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
                    ),
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = false,
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
