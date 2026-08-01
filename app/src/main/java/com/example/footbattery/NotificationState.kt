package com.example.footbattery

data class TransientStatusToken(
    val generation: Long,
    val expiresAtMs: Long
)

private data class TransientStatus(
    val text: String,
    val token: TransientStatusToken
)

/** Concurrency-safe transient result lifecycle, independent of Android's Handler. */
class TransientStatusStore(private val durationMs: Long = 8_000L) {
    private val guard = Any()
    private var generation = 0L
    private var current: TransientStatus? = null

    fun replace(text: String, nowMs: Long): TransientStatusToken = synchronized(guard) {
        val token = TransientStatusToken(++generation, nowMs + durationMs)
        current = TransientStatus(text, token)
        token
    }

    /** Starting an operation deliberately discards any older result hidden behind it. */
    fun beginOperation() = clear()

    fun clear() = synchronized(guard) {
        generation++
        current = null
    }

    fun visibleText(nowMs: Long): String? = synchronized(guard) {
        val value = current ?: return@synchronized null
        if (nowMs >= value.token.expiresAtMs) {
            current = null
            null
        } else {
            value.text
        }
    }

    /** Returns true only when this callback cleared the still-current expired generation. */
    fun expire(token: TransientStatusToken, nowMs: Long): Boolean = synchronized(guard) {
        val value = current
        if (value?.token != token || nowMs < token.expiresAtMs) {
            false
        } else {
            current = null
            true
        }
    }
}

data class StateNotificationModel(
    val display: SnapshotDisplayState,
    val statusText: String?,
    val includeActions: Boolean
)

/** Pure precedence and battery presentation for both live and polling notification renders. */
object NotificationStatePresentation {
    fun create(
        snapshot: SnapshotState,
        liveBatteryLevel: Int?,
        activeOperationText: String?,
        transientText: String?,
        actionsSafe: Boolean
    ): StateNotificationModel = StateNotificationModel(
        display = SnapshotPresentation.create(snapshot, liveBatteryLevel),
        statusText = activeOperationText ?: transientText,
        includeActions = activeOperationText == null && actionsSafe
    )
}

enum class StateNotificationAction {
    CHECK_NOW,
    STANDBY_ON,
    STANDBY_OFF
}

fun stateNotificationActions(
    display: SnapshotDisplayState,
    includeActions: Boolean
): List<StateNotificationAction> {
    if (!includeActions) return emptyList()
    val actions = mutableListOf(StateNotificationAction.CHECK_NOW)
    when (display.standbyAction) {
        StandbyState.ON -> actions += StateNotificationAction.STANDBY_ON
        StandbyState.OFF -> actions += StateNotificationAction.STANDBY_OFF
        StandbyState.UNKNOWN, null -> Unit
    }
    return actions
}

data class LiveBatteryRefreshPlan(
    val refreshOngoing: Boolean,
    val refreshPolling: Boolean
)

fun liveBatteryRefreshPlan(monitoringRequested: Boolean): LiveBatteryRefreshPlan =
    LiveBatteryRefreshPlan(
        refreshOngoing = monitoringRequested,
        refreshPolling = false
    )
