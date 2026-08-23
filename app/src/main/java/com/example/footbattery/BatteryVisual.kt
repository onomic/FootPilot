package com.example.footbattery

enum class BatteryVisualBand {
    UNKNOWN,
    NORMAL,
    WARNING,
    CRITICAL
}

/** Fixed visual semantics; deliberately independent from the configurable alert threshold. */
fun batteryVisualBand(level: Int?): BatteryVisualBand = when {
    level == null -> BatteryVisualBand.UNKNOWN
    level <= 15 -> BatteryVisualBand.CRITICAL
    level <= 35 -> BatteryVisualBand.WARNING
    else -> BatteryVisualBand.NORMAL
}
