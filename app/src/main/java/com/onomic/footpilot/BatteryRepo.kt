package com.onomic.footpilot

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
    /** Whole seconds shown only while a persistent live reconnect is deliberately waiting. */
    val retrySecondsRemaining = MutableStateFlow<Int?>(null)
    /** Whole seconds shown only while a bounded Standby retry is deliberately waiting. */
    val standbyRetrySecondsRemaining = MutableStateFlow<Int?>(null)
    val systemLink = MutableStateFlow("")

    fun ensureInitialized(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val hasSelectedFoot = SelectedFootRepository.current(app) != null
        val saved = if (hasSelectedFoot) Prefs.snapshot(app) else SnapshotState()
        snapshot.value = saved
        level.value = saved.batteryLevel
        status.value = if (hasSelectedFoot) "Idle" else "Add a foot in Settings"
        standbyStatus.value = if (!hasSelectedFoot) {
            ""
        } else if (
            saved.standby == StandbyState.UNKNOWN &&
            saved.completeness != SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
        ) {
            "Check now to verify standby"
        } else {
            ""
        }
    }

    fun resetForFootChange(hasSelectedFoot: Boolean) {
        level.value = null
        snapshot.value = SnapshotState()
        status.value = if (hasSelectedFoot) "Idle" else "Add a foot in Settings"
        standbyStatus.value = if (hasSelectedFoot) "Check now to verify standby" else ""
        running.value = false
        connectionState.value = LiveConnectionState.IDLE
        retrySecondsRemaining.value = null
        standbyRetrySecondsRemaining.value = null
        systemLink.value = ""
    }

    fun applySnapshot(value: SnapshotState) {
        snapshot.value = value
        level.value = value.batteryLevel
        standbyStatus.value = ""
    }

    /** Applies incomplete standby metadata without replacing a newer live battery value. */
    fun applyIncompleteSnapshot(value: SnapshotState) {
        require(value.completeness != SnapshotCompleteness.COMPLETE)
        snapshot.value = value
        standbyStatus.value = ""
    }
}
