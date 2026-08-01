package com.example.footbattery

data class MainScreenMessages(
    val standbyCardMessage: String,
    val footerStatus: String
)

/** Pure status precedence and deduplication for the main screen. */
object MainScreenMessagePresentation {
    fun create(
        activeOperationText: String?,
        verificationMessage: String?,
        standbyStatus: String?,
        generalStatus: String?
    ): MainScreenMessages {
        val cardMessage = sequenceOf(
            activeOperationText,
            verificationMessage,
            standbyStatus
        ).mapNotNull { it.trimmedOrNull() }.firstOrNull().orEmpty()
        val generalMessage = generalStatus.trimmedOrNull().orEmpty()

        return MainScreenMessages(
            standbyCardMessage = cardMessage,
            footerStatus = generalMessage.takeUnless { it == cardMessage }.orEmpty()
        )
    }
}

/** Keeps standby-specific operation wording separate from general connection status. */
fun standbyCardOperationText(operation: BleOperationKind?): String? = when (operation) {
    BleOperationKind.MANUAL_CHECK,
    BleOperationKind.NOTIFICATION_CHECK,
    BleOperationKind.SCHEDULED_CHECK,
    BleOperationKind.LIVE_CONNECT,
    BleOperationKind.LIVE_REFRESH -> "Checking standby..."
    BleOperationKind.STANDBY_ON -> "Turning standby on..."
    BleOperationKind.STANDBY_OFF -> "Turning standby off..."
    else -> null
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
