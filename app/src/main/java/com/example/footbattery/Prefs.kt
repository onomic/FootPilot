package com.example.footbattery

import android.content.Context

/** All persisted settings live here, typed, to avoid stringly-typed mistakes. */
object Prefs {
    private const val FILE = "foot"
    private const val SNAPSHOT_BATTERY = "snapshot_battery"
    private const val SNAPSHOT_STANDBY = "snapshot_standby"
    private const val LAST_CHECKED = "last_checked"
    private const val COMPLETE_SNAPSHOT_V1 = "complete_snapshot_v1"
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

    /** The last complete, mutually verified battery + standby snapshot. */
    fun snapshot(ctx: Context): SnapshotState {
        val prefs = p(ctx)
        val battery = if (prefs.contains(SNAPSHOT_BATTERY)) {
            prefs.getInt(SNAPSHOT_BATTERY, -1).takeIf { it in 0..100 }
        } else {
            null
        }
        return SnapshotState(
            batteryLevel = battery,
            standby = StandbyState.fromPersisted(prefs.getString(SNAPSHOT_STANDBY, null)),
            // Ignore the legacy battery-only timestamp until this version writes snapshot data.
            lastChecked = if (prefs.getBoolean(COMPLETE_SNAPSHOT_V1, false)) {
                prefs.getLong(LAST_CHECKED, 0L)
            } else {
                0L
            }
        )
    }

    /** One editor transaction keeps all three verified snapshot fields coherent. */
    fun saveCompleteSnapshot(ctx: Context, snapshot: SnapshotState) {
        require(snapshot.batteryLevel != null && snapshot.batteryLevel in 0..100)
        require(snapshot.standby != StandbyState.UNKNOWN)
        require(snapshot.lastChecked > 0L)
        p(ctx).edit()
            .putInt(SNAPSHOT_BATTERY, snapshot.batteryLevel!!)
            .putString(SNAPSHOT_STANDBY, snapshot.standby.name)
            .putLong(LAST_CHECKED, snapshot.lastChecked)
            .putBoolean(COMPLETE_SNAPSHOT_V1, true)
            .apply()
    }

    /** Used only when a standby change is confirmed without a later successful battery read. */
    fun saveStandbyOnly(ctx: Context, standby: StandbyState) {
        p(ctx).edit().putString(SNAPSHOT_STANDBY, standby.name).apply()
    }

    fun lastChecked(ctx: Context): Long = snapshot(ctx).lastChecked
}
