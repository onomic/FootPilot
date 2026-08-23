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

data class StateNotificationContent(
    val title: String,
    val batteryLabel: String,
    val batteryValue: String,
    val batteryLevel: Int?,
    val collapsedText: String,
    val standbyText: String,
    val angleSummaryText: String,
    val summaryPreset: FootwearPreset?,
    val summaryPresetName: String?,
    val angleStatusText: String,
    val angleStatusConfirmed: Boolean,
    val operationText: String?,
    val expandedLines: List<String>
)

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

/** Status-only collapsed copy plus the expanded verified ankle/preset hierarchy. */
object StateNotificationContentPresentation {
    fun create(
        display: SnapshotDisplayState,
        ankle: AnkleState = AnkleState(),
        presets: PresetState = PresetState(),
        formattedTime: String?,
        statusText: String?
    ): StateNotificationContent {
        val checkedLine = display.checkedLine(formattedTime)
        val resolvedStatus = statusText?.trim()?.takeIf { it.isNotEmpty() }
        val confirmedMd = ankle.confirmedMd.takeIf { display.standby == StandbyState.OFF }
        val matchedPreset = summaryPreset(presets, confirmedMd)
        val angleStatus = when {
            confirmedMd != null -> "Confirmed ${AnkleProtocol.format(confirmedMd)}"
            ankle.certainty == AnkleCertainty.UNKNOWN_AFTER_COMMAND ->
                ankle.lastVerifiedMd?.let { "Unknown · Last verified ${AnkleProtocol.format(it)}" }
                    ?: "Ankle angle unknown"
            ankle.certainty == AnkleCertainty.UNKNOWN && ankle.lastVerifiedMd != null ->
                "Unknown · Last verified ${AnkleProtocol.format(ankle.lastVerifiedMd)}"
            ankle.confirmedMd != null ->
                "Last verified ${AnkleProtocol.format(requireNotNull(ankle.confirmedMd))}"
            else -> "Ankle angle unknown"
        }
        val angleSummary = listOfNotNull(matchedPreset?.summaryName, angleStatus)
            .joinToString(" · ")
        val expandedLines = buildList {
            add(display.standbyLine)
            add(angleSummary)
            add(checkedLine)
            display.verificationMessage?.let { addDistinct(it) }
            resolvedStatus?.let { addDistinct(it) }
        }
        val collapsedText = when {
            resolvedStatus != null -> resolvedStatus
            display.verificationMessage != null ->
                "${display.standbyLine} · ${display.verificationMessage}"
            ankle.certainty == AnkleCertainty.UNKNOWN && ankle.lastVerifiedMd != null ->
                "${display.standbyLine} · Ankle unknown"
            confirmedMd == null && ankle.confirmedMd != null ->
                "${display.standbyLine} · Last verified ${AnkleProtocol.format(requireNotNull(ankle.confirmedMd))}"
            else -> buildList {
                add(display.standbyLine)
                matchedPreset?.let { add(it.summaryName) }
                confirmedMd?.let { add(AnkleProtocol.format(it)) }
            }.joinToString(" · ")
        }

        return StateNotificationContent(
            title = display.batteryLine,
            batteryLabel = "Battery",
            batteryValue = display.batteryLevel?.let { "$it%" } ?: "—",
            batteryLevel = display.batteryLevel,
            collapsedText = collapsedText,
            standbyText = display.standbyLine,
            angleSummaryText = angleSummary,
            summaryPreset = matchedPreset,
            summaryPresetName = matchedPreset?.summaryName,
            angleStatusText = angleStatus,
            angleStatusConfirmed = confirmedMd != null,
            operationText = resolvedStatus,
            expandedLines = expandedLines
        )
    }

    private fun MutableList<String>.addDistinct(text: String) {
        val resolved = text.trim()
        if (resolved.isNotEmpty() && none { it.trim() == resolved }) add(resolved)
    }
}

enum class StateNotificationAction {
    CHECK_NOW,
    STANDBY,
    AUTO
}

fun stateNotificationActions(
    display: SnapshotDisplayState,
    ankle: AnkleState,
    includeActions: Boolean
): List<StateNotificationAction> {
    if (!includeActions) return emptyList()
    val actions = mutableListOf(StateNotificationAction.CHECK_NOW)
    if (display.standby != StandbyState.UNKNOWN) actions += StateNotificationAction.STANDBY
    if (display.standby == StandbyState.OFF &&
        ankle.certainty == AnkleCertainty.CONFIRMED &&
        ankle.confirmedMd != null
    ) {
        actions += StateNotificationAction.AUTO
    }
    return actions
}

fun notificationPresetActions(
    display: SnapshotDisplayState,
    ankle: AnkleState,
    presets: PresetState,
    includeActions: Boolean
): Set<FootwearPreset> {
    if (!includeActions || display.standby != StandbyState.OFF ||
        ankle.certainty != AnkleCertainty.CONFIRMED || ankle.confirmedMd == null
    ) {
        return emptySet()
    }
    return FootwearPreset.fixedOrder.filterTo(linkedSetOf()) {
        presets.targets.target(it) != null
    }
}

enum class NotificationPresetVisualState {
    ACTIVE_ACTIONABLE,
    ACTIONABLE,
    ACTIVE_UNAVAILABLE,
    UNAVAILABLE
}

data class NotificationPresetPresentation(
    val label: String,
    val visualState: NotificationPresetVisualState,
    val cellAlpha: Float,
    val imageAlpha: Int
)

/** Keeps movement safety and visual affordance aligned without decorating labels with symbols. */
fun notificationPresetPresentation(
    preset: FootwearPreset,
    physicallyActive: Boolean,
    actionable: Boolean
): NotificationPresetPresentation {
    val visualState = when {
        physicallyActive && actionable -> NotificationPresetVisualState.ACTIVE_ACTIONABLE
        actionable -> NotificationPresetVisualState.ACTIONABLE
        physicallyActive -> NotificationPresetVisualState.ACTIVE_UNAVAILABLE
        else -> NotificationPresetVisualState.UNAVAILABLE
    }
    return NotificationPresetPresentation(
        label = preset.displayName,
        visualState = visualState,
        cellAlpha = when (visualState) {
            NotificationPresetVisualState.ACTIVE_ACTIONABLE,
            NotificationPresetVisualState.ACTIONABLE -> 1f
            NotificationPresetVisualState.ACTIVE_UNAVAILABLE -> 0.58f
            NotificationPresetVisualState.UNAVAILABLE -> 0.45f
        },
        imageAlpha = when (visualState) {
            NotificationPresetVisualState.ACTIVE_ACTIONABLE,
            NotificationPresetVisualState.ACTIONABLE -> 255
            NotificationPresetVisualState.ACTIVE_UNAVAILABLE -> 148
            NotificationPresetVisualState.UNAVAILABLE -> 92
        }
    )
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

/** Notifications show a stable wait message and are not rebuilt for each countdown tick. */
fun standbyRetryNotificationText(secondsRemaining: Int?): String? =
    secondsRemaining?.takeIf { it > 0 }?.let { "Retrying standby..." }
