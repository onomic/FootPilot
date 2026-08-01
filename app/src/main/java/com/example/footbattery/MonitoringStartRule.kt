package com.example.footbattery

/** Pure UI rule; the click handler repeats the Bluetooth check to close race windows. */
fun canStartMonitoring(running: Boolean, busy: Boolean, bluetoothAvailable: Boolean): Boolean =
    !running && !busy && bluetoothAvailable

private val BLUETOOTH_UNAVAILABLE_STATUSES = setOf(
    "Turn on Bluetooth",
    "Bluetooth permission is required"
)

/** Pure status transition used by the Compose availability observer. */
fun bluetoothAvailabilityStatus(
    currentStatus: String,
    bluetoothAvailable: Boolean,
    monitoring: Boolean,
    unavailableStatus: String
): String = when {
    monitoring -> currentStatus
    !bluetoothAvailable -> unavailableStatus
    currentStatus in BLUETOOTH_UNAVAILABLE_STATUSES -> "Idle"
    else -> currentStatus
}
