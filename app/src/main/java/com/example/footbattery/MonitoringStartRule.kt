package com.example.footbattery

/** Pure UI rule; the click handler repeats the Bluetooth check to close race windows. */
fun canStartMonitoring(running: Boolean, busy: Boolean, bluetoothAvailable: Boolean): Boolean =
    !running && !busy && bluetoothAvailable
