package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotPersistenceTest {
    @Test fun ambiguousAfterCommandStateSurvivesPersistenceReload() {
        val ambiguous = SnapshotState(
            batteryLevel = 70,
            standby = StandbyState.UNKNOWN,
            lastChecked = 100L,
            completeness = SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
        )

        val reloaded = SnapshotPersistence.decode(SnapshotPersistence.encode(ambiguous))

        assertEquals(ambiguous, reloaded)
    }

    @Test fun ambiguousCompletenessNeverReloadsAStaleKnownStandby() {
        val reloaded = SnapshotPersistence.decode(
            StoredSnapshot(
                batteryLevel = 70,
                standbyName = StandbyState.OFF.name,
                lastChecked = 100L,
                hasCompleteSnapshotV1 = true,
                completenessName =
                    SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND.name
            )
        )

        assertEquals(StandbyState.UNKNOWN, reloaded.standby)
        assertEquals(
            SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND,
            reloaded.completeness
        )
    }

    @Test fun batteryPendingStateSurvivesPersistenceReload() {
        val pending = SnapshotState(
            batteryLevel = 70,
            standby = StandbyState.ON,
            lastChecked = 100L,
            completeness = SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING
        )

        val reloaded = SnapshotPersistence.decode(SnapshotPersistence.encode(pending))

        assertEquals(pending, reloaded)
    }

    @Test fun preferencesWithoutCompletenessFieldDefaultToComplete() {
        val reloaded = SnapshotPersistence.decode(
            StoredSnapshot(
                batteryLevel = 70,
                standbyName = StandbyState.OFF.name,
                lastChecked = 100L,
                hasCompleteSnapshotV1 = true,
                completenessName = null
            )
        )

        assertEquals(SnapshotCompleteness.COMPLETE, reloaded.completeness)
        assertEquals(SnapshotState(70, StandbyState.OFF, 100L), reloaded)
    }

    @Test fun missingStandbyRemainsUnknownDuringMigration() {
        val reloaded = SnapshotPersistence.decode(
            StoredSnapshot(
                batteryLevel = 70,
                standbyName = null,
                lastChecked = 100L,
                hasCompleteSnapshotV1 = false,
                completenessName = null
            )
        )

        assertEquals(StandbyState.UNKNOWN, reloaded.standby)
        assertEquals(70, reloaded.batteryLevel)
        assertEquals(0L, reloaded.lastChecked)
    }

    @Test fun invalidLegacyBatteryIsNotConvertedToZero() {
        val reloaded = SnapshotPersistence.decode(
            StoredSnapshot(
                batteryLevel = -1,
                standbyName = null,
                lastChecked = 0L,
                hasCompleteSnapshotV1 = false,
                completenessName = null
            )
        )

        assertNull(reloaded.batteryLevel)
        assertEquals(StandbyState.UNKNOWN, reloaded.standby)
    }
}
