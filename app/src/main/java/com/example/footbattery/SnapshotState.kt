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

data class SnapshotState(
    val batteryLevel: Int? = null,
    val standby: StandbyState = StandbyState.UNKNOWN,
    val lastChecked: Long = 0L
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
    val standbyChangeConfirmed: Boolean
)

/** Pure state transition rules used by both production persistence and local unit tests. */
object SnapshotReducer {
    fun reduce(previous: SnapshotState, event: SnapshotEvent): SnapshotReduction = when (event) {
        is SnapshotEvent.NormalCheck -> {
            val standby = event.standby
            if (event.batteryLevel != null && standby != null && standby != StandbyState.UNKNOWN) {
                SnapshotReduction(
                    snapshot = SnapshotState(event.batteryLevel, standby, event.checkedAt),
                    completeSnapshotSaved = true,
                    standbyChangeConfirmed = false
                )
            } else {
                SnapshotReduction(previous, completeSnapshotSaved = false, standbyChangeConfirmed = false)
            }
        }

        is SnapshotEvent.StandbyChange -> {
            val confirmed = event.verified && event.finalState == event.requested
            when {
                confirmed && event.batteryLevel != null -> SnapshotReduction(
                    snapshot = SnapshotState(event.batteryLevel, event.requested, event.checkedAt),
                    completeSnapshotSaved = true,
                    standbyChangeConfirmed = true
                )

                confirmed -> SnapshotReduction(
                    snapshot = previous.copy(standby = event.requested),
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = true
                )

                event.finalState != null && event.finalState != StandbyState.UNKNOWN -> SnapshotReduction(
                    snapshot = previous.copy(standby = event.finalState),
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = false
                )

                event.ambiguous -> SnapshotReduction(
                    snapshot = previous.copy(standby = StandbyState.UNKNOWN),
                    completeSnapshotSaved = false,
                    standbyChangeConfirmed = false
                )

                else -> SnapshotReduction(previous, completeSnapshotSaved = false, standbyChangeConfirmed = false)
            }
        }
    }
}
