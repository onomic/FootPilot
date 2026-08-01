package com.example.footbattery

import android.content.Context

/** All persisted settings live here, typed, to avoid stringly-typed mistakes. */
object Prefs {
    private const val FILE = "foot"
    private const val SNAPSHOT_BATTERY = "snapshot_battery"
    private const val SNAPSHOT_STANDBY = "snapshot_standby"
    private const val LAST_CHECKED = "last_checked"
    private const val COMPLETE_SNAPSHOT_V1 = "complete_snapshot_v1"
    private const val SNAPSHOT_COMPLETENESS = "snapshot_completeness"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun threshold(ctx: Context): Int = p(ctx).getInt("threshold", FootConfig.DEFAULT_LOW_BATTERY_THRESHOLD)
    fun setThreshold(ctx: Context, v: Int) = p(ctx).edit().putInt("threshold", v).apply()

    fun polling(ctx: Context): Boolean = p(ctx).getBoolean("polling", false)
    fun setPolling(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("polling", v).apply()

    fun intervalMin(ctx: Context): Int = p(ctx).getInt("interval_min", 60)
    fun setIntervalMin(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_min", v).apply()

    fun monitoring(ctx: Context): Boolean = p(ctx).getBoolean("monitoring", false)
    fun setMonitoring(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("monitoring", v).apply()

    // "armed" = ready to fire a low alert. Re-arms once charged back above threshold.
    fun armed(ctx: Context): Boolean = p(ctx).getBoolean("armed", true)
    fun setArmed(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("armed", v).apply()

    // Pairing PIN the app auto-submits when the foot asks to bond. Empty = none saved.
    fun pairingCode(ctx: Context): String = p(ctx).getString("pairing_code", "") ?: ""
    fun setPairingCode(ctx: Context, v: String) = p(ctx).edit().putString("pairing_code", v.trim()).apply()

    /** Last complete battery/time plus standby verification and completeness metadata. */
    fun snapshot(ctx: Context): SnapshotState {
        val prefs = p(ctx)
        val battery = if (prefs.contains(SNAPSHOT_BATTERY)) {
            prefs.getInt(SNAPSHOT_BATTERY, -1).takeIf { it in 0..100 }
        } else {
            null
        }
        return SnapshotPersistence.decode(
            StoredSnapshot(
                batteryLevel = battery,
                standbyName = prefs.getString(SNAPSHOT_STANDBY, null),
                lastChecked = prefs.getLong(LAST_CHECKED, 0L),
                hasCompleteSnapshotV1 = prefs.getBoolean(COMPLETE_SNAPSHOT_V1, false),
                completenessName = prefs.getString(SNAPSHOT_COMPLETENESS, null)
            )
        )
    }

    /** One editor transaction keeps all verified snapshot fields coherent. */
    fun saveCompleteSnapshot(ctx: Context, snapshot: SnapshotState) {
        require(snapshot.batteryLevel != null && snapshot.batteryLevel in 0..100)
        require(snapshot.standby != StandbyState.UNKNOWN)
        require(snapshot.lastChecked > 0L)
        require(snapshot.completeness == SnapshotCompleteness.COMPLETE)
        val stored = SnapshotPersistence.encode(snapshot)
        p(ctx).edit()
            .putInt(SNAPSHOT_BATTERY, requireNotNull(stored.batteryLevel))
            .putString(SNAPSHOT_STANDBY, stored.standbyName)
            .putLong(LAST_CHECKED, stored.lastChecked)
            .putBoolean(COMPLETE_SNAPSHOT_V1, true)
            .putString(SNAPSHOT_COMPLETENESS, stored.completenessName)
            .apply()
    }

    /** Persists incomplete verification metadata while retaining complete battery/time fields. */
    fun saveIncompleteSnapshot(ctx: Context, snapshot: SnapshotState) {
        when (snapshot.completeness) {
            SnapshotCompleteness.COMPLETE -> error("A complete snapshot must use saveCompleteSnapshot")
            SnapshotCompleteness.STANDBY_CONFIRMED_BATTERY_PENDING ->
                require(snapshot.standby != StandbyState.UNKNOWN)
            SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND ->
                require(snapshot.standby == StandbyState.UNKNOWN)
        }
        val stored = SnapshotPersistence.encode(snapshot)
        val editor = p(ctx).edit()
            .putString(SNAPSHOT_STANDBY, stored.standbyName)
            .putString(SNAPSHOT_COMPLETENESS, stored.completenessName)
        stored.batteryLevel?.let { editor.putInt(SNAPSHOT_BATTERY, it) }
        if (stored.hasCompleteSnapshotV1) {
            editor.putLong(LAST_CHECKED, stored.lastChecked)
                .putBoolean(COMPLETE_SNAPSHOT_V1, true)
        }
        editor.apply()
    }

    fun lastChecked(ctx: Context): Long = snapshot(ctx).lastChecked
}
