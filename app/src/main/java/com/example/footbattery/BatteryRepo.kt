package com.example.footbattery

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared, in-memory state the service writes and the UI observes. */
object BatteryRepo {
    val level = MutableStateFlow<Int?>(null)
    val status = MutableStateFlow("Idle")
    val running = MutableStateFlow(false)
    val systemLink = MutableStateFlow("")
}
