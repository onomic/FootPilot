package com.example.footbattery

/** Android-free representation used to make snapshot migration and reload deterministic. */
data class StoredSnapshot(
    val batteryLevel: Int?,
    val standbyName: String?,
    val lastChecked: Long,
    val hasCompleteSnapshotV1: Boolean,
    val completenessName: String?
)

object SnapshotPersistence {
    fun decode(stored: StoredSnapshot): SnapshotState {
        val completeness = SnapshotCompleteness.fromPersisted(stored.completenessName)
        return SnapshotState(
            batteryLevel = stored.batteryLevel?.takeIf { it in 0..100 },
            standby = if (
                completeness == SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
            ) {
                StandbyState.UNKNOWN
            } else {
                StandbyState.fromPersisted(stored.standbyName)
            },
            // Ignore a legacy battery-only timestamp until Standby v1 wrote snapshot data.
            lastChecked = if (stored.hasCompleteSnapshotV1) stored.lastChecked else 0L,
            completeness = completeness
        )
    }

    fun encode(snapshot: SnapshotState): StoredSnapshot = StoredSnapshot(
        batteryLevel = snapshot.batteryLevel?.takeIf { it in 0..100 },
        standbyName = snapshot.standby.name,
        lastChecked = snapshot.lastChecked,
        hasCompleteSnapshotV1 = snapshot.lastChecked > 0L,
        completenessName = snapshot.completeness.name
    )
}
