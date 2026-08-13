package com.example.footbattery

data class AnkleValuePresentation(
    val angleText: String,
    val statusText: String,
    val historicalText: String?,
    val isCurrentConfirmed: Boolean,
    val movementEnabled: Boolean,
    val minusEnabled: Boolean,
    val plusEnabled: Boolean
)

object AnklePresentation {
    fun create(
        state: AnkleState,
        standby: StandbyState,
        controlsReady: Boolean
    ): AnkleValuePresentation {
        val confirmed = state.confirmedMd
        val lastVerified = state.lastVerifiedMd
        val blockedByStandby = standby != StandbyState.OFF
        val movementEnabled = controlsReady && !blockedByStandby &&
            state.operation == AnkleOperation.IDLE && confirmed != null

        return when {
            state.certainty == AnkleCertainty.UNKNOWN_AFTER_COMMAND -> AnkleValuePresentation(
                angleText = "Unknown",
                statusText = "Angle not verified after command",
                historicalText = lastVerified?.let { "Last verified ${AnkleProtocol.format(it)}" },
                isCurrentConfirmed = false,
                movementEnabled = false,
                minusEnabled = false,
                plusEnabled = false
            )

            confirmed != null && blockedByStandby -> AnkleValuePresentation(
                angleText = AnkleProtocol.format(confirmed),
                statusText = "Last verified ${AnkleProtocol.format(confirmed)}",
                historicalText = "Movement unavailable while standby is ${standby.displayName()}",
                isCurrentConfirmed = false,
                movementEnabled = false,
                minusEnabled = false,
                plusEnabled = false
            )

            confirmed != null -> AnkleValuePresentation(
                angleText = AnkleProtocol.format(confirmed),
                statusText = "Confirmed ${AnkleProtocol.format(confirmed)}",
                historicalText = null,
                isCurrentConfirmed = true,
                movementEnabled = movementEnabled,
                minusEnabled = movementEnabled &&
                    AnkleProtocol.fineTarget(confirmed, FineAdjustment.MINUS) != null,
                plusEnabled = movementEnabled &&
                    AnkleProtocol.fineTarget(confirmed, FineAdjustment.PLUS) != null
            )

            lastVerified != null -> AnkleValuePresentation(
                angleText = "Unknown",
                statusText = "Ankle angle not currently verified",
                historicalText = "Last verified ${AnkleProtocol.format(lastVerified)}",
                isCurrentConfirmed = false,
                movementEnabled = false,
                minusEnabled = false,
                plusEnabled = false
            )

            else -> AnkleValuePresentation(
                angleText = "Unknown",
                statusText = "Check now to verify ankle angle",
                historicalText = null,
                isCurrentConfirmed = false,
                movementEnabled = false,
                minusEnabled = false,
                plusEnabled = false
            )
        }
    }
}
