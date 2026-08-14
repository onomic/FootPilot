package com.example.footbattery

import android.content.Context

/** All persisted settings live here, typed, to avoid stringly-typed mistakes. */
object Prefs {
    private const val FILE = "foot"
    private const val SNAPSHOT_BATTERY = FootPreferenceKeys.SNAPSHOT_BATTERY
    private const val SNAPSHOT_STANDBY = FootPreferenceKeys.SNAPSHOT_STANDBY
    private const val LAST_CHECKED = FootPreferenceKeys.LAST_CHECKED
    private const val COMPLETE_SNAPSHOT_V1 = FootPreferenceKeys.COMPLETE_SNAPSHOT_V1
    private const val SNAPSHOT_COMPLETENESS = FootPreferenceKeys.SNAPSHOT_COMPLETENESS
    private const val ANKLE_MILLIDEGREES = FootPreferenceKeys.ANKLE_MILLIDEGREES
    private const val ANKLE_VERIFIED_AT = FootPreferenceKeys.ANKLE_VERIFIED_AT
    private const val ANKLE_CERTAINTY = FootPreferenceKeys.ANKLE_CERTAINTY
    private const val PRESET_BAREFOOT_MD = FootPreferenceKeys.PRESET_BAREFOOT_MD
    private const val PRESET_RUNNING_MD = FootPreferenceKeys.PRESET_RUNNING_MD
    private const val PRESET_DRESS_MD = FootPreferenceKeys.PRESET_DRESS_MD
    private const val PRESET_BOOTS_MD = FootPreferenceKeys.PRESET_BOOTS_MD
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun threshold(ctx: Context): Int =
        p(ctx).getInt(FootPreferenceKeys.THRESHOLD, FootConfig.DEFAULT_LOW_BATTERY_THRESHOLD)
    fun setThreshold(ctx: Context, v: Int) =
        p(ctx).edit().putInt(FootPreferenceKeys.THRESHOLD, v).apply()

    fun polling(ctx: Context): Boolean = p(ctx).getBoolean(FootPreferenceKeys.POLLING, false)
    fun setPolling(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean(FootPreferenceKeys.POLLING, v).apply()

    fun intervalMin(ctx: Context): Int = p(ctx).getInt(FootPreferenceKeys.INTERVAL_MIN, 60)
    fun setIntervalMin(ctx: Context, v: Int) =
        p(ctx).edit().putInt(FootPreferenceKeys.INTERVAL_MIN, v).apply()

    fun monitoring(ctx: Context): Boolean = p(ctx).getBoolean(FootPreferenceKeys.MONITORING, false)
    fun setMonitoring(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean(FootPreferenceKeys.MONITORING, v).apply()

    // "armed" = ready to fire a low alert. Re-arms once charged back above threshold.
    fun armed(ctx: Context): Boolean = p(ctx).getBoolean(FootPreferenceKeys.ARMED, true)
    fun setArmed(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean(FootPreferenceKeys.ARMED, v).apply()

    // Pairing PIN the app auto-submits when the foot asks to bond. Empty = none saved.
    fun pairingCode(ctx: Context): String =
        p(ctx).getString(FootPreferenceKeys.PAIRING_CODE, "") ?: ""
    fun setPairingCode(ctx: Context, v: String) =
        p(ctx).edit().putString(FootPreferenceKeys.PAIRING_CODE, v.trim()).apply()

    fun selectedFoot(ctx: Context): SelectedFoot? {
        val prefs = p(ctx)
        return SelectedFootPersistence.decode(
            prefs.getString(FootPreferenceKeys.SELECTED_FOOT_NAME, null),
            prefs.getString(FootPreferenceKeys.SELECTED_FOOT_ADDRESS, null)
        )
    }

    /** Selection and all device-specific reset fields are committed as one transaction. */
    fun replaceSelectedFoot(ctx: Context, selectedFoot: SelectedFoot?): Boolean {
        val validated = selectedFoot?.let {
            SelectedFootPersistence.decode(it.name, it.address) ?: return false
        }
        val editor = p(ctx).edit()
        if (validated == null) {
            editor.remove(FootPreferenceKeys.SELECTED_FOOT_NAME)
                .remove(FootPreferenceKeys.SELECTED_FOOT_ADDRESS)
        } else {
            editor.putString(FootPreferenceKeys.SELECTED_FOOT_NAME, validated.name)
                .putString(FootPreferenceKeys.SELECTED_FOOT_ADDRESS, validated.address)
        }
        val reset = deviceStateResetMutation()
        reset.removedKeys.forEach { editor.remove(it) }
        reset.booleanValues.forEach { (key, value) -> editor.putBoolean(key, value) }
        return editor.commit()
    }

    fun disableConnectionAutomation(ctx: Context) {
        p(ctx).edit()
            .putBoolean(FootPreferenceKeys.POLLING, false)
            .putBoolean(FootPreferenceKeys.MONITORING, false)
            .apply()
    }

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

    fun ankleState(ctx: Context): StoredAnkleState {
        val prefs = p(ctx)
        return StoredAnkleState(
            millidegrees = if (prefs.contains(ANKLE_MILLIDEGREES)) {
                prefs.getInt(ANKLE_MILLIDEGREES, 0)
            } else {
                null
            },
            verifiedAt = prefs.getLong(ANKLE_VERIFIED_AT, 0L).takeIf { it > 0L },
            certaintyName = prefs.getString(ANKLE_CERTAINTY, null)
        )
    }

    fun saveAnkleState(ctx: Context, state: AnkleState) {
        val stored = AnklePersistence.encode(state)
        val editor = p(ctx).edit().putString(ANKLE_CERTAINTY, stored.certaintyName)
        if (stored.millidegrees == null) {
            editor.remove(ANKLE_MILLIDEGREES).remove(ANKLE_VERIFIED_AT)
        } else {
            editor.putInt(ANKLE_MILLIDEGREES, stored.millidegrees)
            stored.verifiedAt?.let { editor.putLong(ANKLE_VERIFIED_AT, it) }
                ?: editor.remove(ANKLE_VERIFIED_AT)
        }
        editor.apply()
    }

    fun presetTargets(ctx: Context): PresetTargets {
        val prefs = p(ctx)
        fun target(key: String): Int? = if (prefs.contains(key)) {
            prefs.getInt(key, 0).takeIf(AnkleProtocol::isSupported)
        } else {
            null
        }
        return PresetTargets(
            barefootMd = target(PRESET_BAREFOOT_MD),
            runningMd = target(PRESET_RUNNING_MD),
            dressMd = target(PRESET_DRESS_MD),
            bootsMd = target(PRESET_BOOTS_MD)
        )
    }

    fun savePresetTarget(ctx: Context, preset: FootwearPreset, confirmedMd: Int) {
        require(AnkleProtocol.isSupported(confirmedMd))
        val key = when (preset) {
            FootwearPreset.BAREFOOT -> PRESET_BAREFOOT_MD
            FootwearPreset.RUNNING -> PRESET_RUNNING_MD
            FootwearPreset.DRESS -> PRESET_DRESS_MD
            FootwearPreset.BOOTS -> PRESET_BOOTS_MD
        }
        p(ctx).edit().putInt(key, confirmedMd).apply()
    }
}
