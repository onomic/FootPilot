package com.example.footbattery

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

enum class LiveConnectionState {
    IDLE,
    CONNECTING,
    DISCOVERING,
    INITIALIZING,
    READY,
    DISCONNECTING,
    FAILED
}

/** Shared process state observed by the UI and notification builders. */
object BatteryRepo {
    private val initialized = AtomicBoolean(false)

    /** May contain a newer live notification value than [snapshot]. */
    val level = MutableStateFlow<Int?>(null)
    val snapshot = MutableStateFlow(SnapshotState())
    val status = MutableStateFlow("Idle")
    val standbyStatus = MutableStateFlow("Check now to verify standby")
    val running = MutableStateFlow(false)
    val connectionState = MutableStateFlow(LiveConnectionState.IDLE)
    val systemLink = MutableStateFlow("")

    fun ensureInitialized(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val saved = Prefs.snapshot(ctx.applicationContext)
        snapshot.value = saved
        level.value = saved.batteryLevel
        standbyStatus.value = if (saved.standby == StandbyState.UNKNOWN) {
            "Check now to verify standby"
        } else {
            ""
        }
    }

    fun applySnapshot(value: SnapshotState) {
        snapshot.value = value
        level.value = value.batteryLevel
        standbyStatus.value = ""
    }

    fun applyStandbyOnly(value: StandbyState) {
        snapshot.value = snapshot.value.copy(standby = value)
    }
}
