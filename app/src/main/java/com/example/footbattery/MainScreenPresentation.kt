package com.example.footbattery

enum class MainScreenHeightClass {
    COMPACT,
    REGULAR,
    TALL
}

data class MainScreenLayoutSpec(
    val heightClass: MainScreenHeightClass,
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
    val headerToGaugeGapDp: Float,
    val gaugeSizeDp: Float,
    val gaugeValueFontSizeSp: Float,
    val gaugePercentFontSizeSp: Float,
    val gaugeToDeviceGapDp: Float,
    val deviceToThresholdGapDp: Float,
    val metadataToCardGapDp: Float,
    val cardMinHeightDp: Float,
    val cardToStatusGapDp: Float,
    val statusSlotHeightDp: Float,
    val actionGapDp: Float
)

/**
 * Pure geometry decision based on the actual Compose content height.
 *
 * Font scale reduces the effective height so a moderately enlarged type setting selects a
 * tighter geometry before text and controls need the room.
 */
fun mainScreenLayoutSpec(
    availableHeightDp: Float,
    fontScale: Float
): MainScreenLayoutSpec {
    val effectiveHeightDp = availableHeightDp / fontScale.coerceAtLeast(1f)
    val heightClass = when {
        effectiveHeightDp < 700f -> MainScreenHeightClass.COMPACT
        effectiveHeightDp < 820f -> MainScreenHeightClass.REGULAR
        else -> MainScreenHeightClass.TALL
    }

    return when (heightClass) {
        MainScreenHeightClass.COMPACT -> MainScreenLayoutSpec(
            heightClass = heightClass,
            horizontalPaddingDp = 20f,
            verticalPaddingDp = 14f,
            headerToGaugeGapDp = 8f,
            gaugeSizeDp = 132f,
            gaugeValueFontSizeSp = 40f,
            gaugePercentFontSizeSp = 15f,
            gaugeToDeviceGapDp = 6f,
            deviceToThresholdGapDp = 4f,
            metadataToCardGapDp = 10f,
            cardMinHeightDp = 92f,
            cardToStatusGapDp = 8f,
            statusSlotHeightDp = 36f,
            actionGapDp = 8f
        )
        MainScreenHeightClass.REGULAR -> MainScreenLayoutSpec(
            heightClass = heightClass,
            horizontalPaddingDp = 22f,
            verticalPaddingDp = 18f,
            headerToGaugeGapDp = 10f,
            gaugeSizeDp = 148f,
            gaugeValueFontSizeSp = 46f,
            gaugePercentFontSizeSp = 16f,
            gaugeToDeviceGapDp = 7f,
            deviceToThresholdGapDp = 4f,
            metadataToCardGapDp = 12f,
            cardMinHeightDp = 96f,
            cardToStatusGapDp = 8f,
            statusSlotHeightDp = 36f,
            actionGapDp = 9f
        )
        MainScreenHeightClass.TALL -> MainScreenLayoutSpec(
            heightClass = heightClass,
            horizontalPaddingDp = 24f,
            verticalPaddingDp = 20f,
            headerToGaugeGapDp = 12f,
            gaugeSizeDp = 168f,
            gaugeValueFontSizeSp = 52f,
            gaugePercentFontSizeSp = 18f,
            gaugeToDeviceGapDp = 8f,
            deviceToThresholdGapDp = 4f,
            metadataToCardGapDp = 14f,
            cardMinHeightDp = 100f,
            cardToStatusGapDp = 10f,
            statusSlotHeightDp = 36f,
            actionGapDp = 10f
        )
    }
}

enum class MainScreenMode {
    LIVE,
    POLLING,
    IDLE
}

data class MainScreenModePresentation(
    val mode: MainScreenMode,
    val label: String,
    val usesActiveColor: Boolean,
    val pulses: Boolean
)

/** Resolves the header mode without implying polling during an active live session. */
fun mainScreenModePresentation(
    liveReady: Boolean,
    monitoringActive: Boolean,
    pollingEnabled: Boolean
): MainScreenModePresentation {
    val mode = when {
        liveReady -> MainScreenMode.LIVE
        pollingEnabled && !monitoringActive -> MainScreenMode.POLLING
        else -> MainScreenMode.IDLE
    }
    return when (mode) {
        MainScreenMode.LIVE -> MainScreenModePresentation(
            mode = mode,
            label = "LIVE",
            usesActiveColor = true,
            pulses = true
        )
        MainScreenMode.POLLING -> MainScreenModePresentation(
            mode = mode,
            label = "POLLING",
            usesActiveColor = true,
            pulses = false
        )
        MainScreenMode.IDLE -> MainScreenModePresentation(
            mode = mode,
            label = "IDLE",
            usesActiveColor = false,
            pulses = false
        )
    }
}

enum class MainScreenStatusKind {
    NONE,
    ACTIVE_OPERATION,
    VERIFICATION_WARNING,
    STANDBY_STATUS,
    GENERAL_STATUS
}

data class MainScreenPresentation(
    val statusText: String,
    val statusKind: MainScreenStatusKind
) {
    companion object {
        /** Resolves the only transient or warning message shown on the main screen. */
        fun create(
            activeOperationText: String?,
            verificationMessage: String?,
            standbyStatus: String?,
            generalStatus: String?
        ): MainScreenPresentation {
            val candidates = listOf(
                MainScreenStatusKind.ACTIVE_OPERATION to activeOperationText,
                MainScreenStatusKind.VERIFICATION_WARNING to verificationMessage,
                MainScreenStatusKind.STANDBY_STATUS to standbyStatus,
                MainScreenStatusKind.GENERAL_STATUS to generalStatus
            )
            val resolved = candidates.firstNotNullOfOrNull { (kind, text) ->
                text.trimmedOrNull()?.let { kind to it }
            }
            return MainScreenPresentation(
                statusText = resolved?.second.orEmpty(),
                statusKind = resolved?.first ?: MainScreenStatusKind.NONE
            )
        }
    }
}

/** Keeps the established standby wording while also covering disconnect operations. */
fun mainScreenOperationText(operation: BleOperationKind?): String? = when (operation) {
    BleOperationKind.MANUAL_CHECK,
    BleOperationKind.NOTIFICATION_CHECK,
    BleOperationKind.SCHEDULED_CHECK,
    BleOperationKind.LIVE_CONNECT,
    BleOperationKind.LIVE_REFRESH -> "Checking standby..."
    BleOperationKind.FOOT_VERIFICATION -> "Verifying foot..."
    BleOperationKind.FOOT_SELECTION_CHANGE -> "Updating foot..."
    BleOperationKind.STANDBY_ON -> "Turning standby on..."
    BleOperationKind.STANDBY_OFF -> "Turning standby off..."
    BleOperationKind.STANDBY_TOGGLE -> "Updating standby..."
    BleOperationKind.ANKLE_ADJUST -> "Adjusting ankle..."
    BleOperationKind.PRESET_APPLY -> "Applying preset..."
    BleOperationKind.AUTO_ALIGN -> "Automatic alignment"
    BleOperationKind.DISCONNECT -> "Disconnecting..."
    null -> null
}

enum class MainScreenContextualActionType {
    START,
    DISCONNECT
}

data class MainScreenContextualAction(
    val type: MainScreenContextualActionType,
    val label: String,
    val enabled: Boolean
)

/** Pure contextual-action selection; enablement matches the existing Start/Disconnect rules. */
fun mainScreenContextualAction(
    running: Boolean,
    busy: Boolean,
    bluetoothAvailable: Boolean,
    footSelected: Boolean
): MainScreenContextualAction = if (running) {
    MainScreenContextualAction(
        type = MainScreenContextualActionType.DISCONNECT,
        label = "Disconnect",
        enabled = !busy
    )
} else {
    MainScreenContextualAction(
        type = MainScreenContextualActionType.START,
        label = "Start",
        enabled = canStartMonitoring(running, busy, bluetoothAvailable, footSelected)
    )
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
