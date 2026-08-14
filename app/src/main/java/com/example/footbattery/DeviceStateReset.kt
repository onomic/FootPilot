package com.example.footbattery

/** Preference keys whose values describe one physical prosthesis. */
internal object FootPreferenceKeys {
    const val THRESHOLD = "threshold"
    const val POLLING = "polling"
    const val INTERVAL_MIN = "interval_min"
    const val MONITORING = "monitoring"
    const val ARMED = "armed"
    const val PAIRING_CODE = "pairing_code"
    const val SELECTED_FOOT_NAME = "selected_foot_name"
    const val SELECTED_FOOT_ADDRESS = "selected_foot_address"

    const val SNAPSHOT_BATTERY = "snapshot_battery"
    const val SNAPSHOT_STANDBY = "snapshot_standby"
    const val LAST_CHECKED = "last_checked"
    const val COMPLETE_SNAPSHOT_V1 = "complete_snapshot_v1"
    const val SNAPSHOT_COMPLETENESS = "snapshot_completeness"
    const val ANKLE_MILLIDEGREES = "ankle_millidegrees"
    const val ANKLE_VERIFIED_AT = "ankle_verified_at"
    const val ANKLE_CERTAINTY = "ankle_certainty"
    const val PRESET_BAREFOOT_MD = "preset_barefoot_md"
    const val PRESET_RUNNING_MD = "preset_running_md"
    const val PRESET_DRESS_MD = "preset_dress_md"
    const val PRESET_BOOTS_MD = "preset_boots_md"
}

data class DeviceStateResetMutation(
    val removedKeys: Set<String>,
    val booleanValues: Map<String, Boolean>
) {
    /** Android-free application used by focused unit tests. */
    fun applyTo(values: Map<String, Any?>): Map<String, Any?> = values.toMutableMap().apply {
        removedKeys.forEach(::remove)
        putAll(booleanValues)
    }
}

/** One source of truth for clearing state that must never cross physical feet. */
fun deviceStateResetMutation(): DeviceStateResetMutation = DeviceStateResetMutation(
    removedKeys = setOf(
        FootPreferenceKeys.SNAPSHOT_BATTERY,
        FootPreferenceKeys.SNAPSHOT_STANDBY,
        FootPreferenceKeys.LAST_CHECKED,
        FootPreferenceKeys.COMPLETE_SNAPSHOT_V1,
        FootPreferenceKeys.SNAPSHOT_COMPLETENESS,
        FootPreferenceKeys.ANKLE_MILLIDEGREES,
        FootPreferenceKeys.ANKLE_VERIFIED_AT,
        FootPreferenceKeys.ANKLE_CERTAINTY,
        FootPreferenceKeys.PRESET_BAREFOOT_MD,
        FootPreferenceKeys.PRESET_RUNNING_MD,
        FootPreferenceKeys.PRESET_DRESS_MD,
        FootPreferenceKeys.PRESET_BOOTS_MD
    ),
    booleanValues = mapOf(
        FootPreferenceKeys.ARMED to true,
        FootPreferenceKeys.POLLING to false,
        FootPreferenceKeys.MONITORING to false
    )
)
