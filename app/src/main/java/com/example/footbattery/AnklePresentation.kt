package com.example.footbattery

import java.util.Locale
import kotlin.math.abs

data class AnkleValuePresentation(
    val angleText: String,
    val angleContentDescription: String,
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
        val confirmed = state.confirmedMd?.takeIf(AnkleProtocol::isSupported)
        val lastVerified = state.lastVerifiedMd?.takeIf(AnkleProtocol::isSupported)
        val blockedByStandby = standby != StandbyState.OFF
        val movementEnabled = controlsReady && !blockedByStandby &&
            state.operation == AnkleOperation.IDLE && confirmed != null

        return when {
            state.certainty == AnkleCertainty.UNKNOWN_AFTER_COMMAND -> {
                val historicalAngle = lastVerified?.let(AnkleProtocol::format)
                AnkleValuePresentation(
                    angleText = historicalAngle ?: "Unknown",
                    angleContentDescription = lastVerified?.let(::historicalAngleDescription)
                        ?: "Ankle angle unknown",
                    statusText = historicalAngle?.let {
                        "Last verified $it · not verified after adjustment"
                    } ?: "Angle not verified after adjustment",
                    historicalText = null,
                    isCurrentConfirmed = false,
                    movementEnabled = false,
                    minusEnabled = false,
                    plusEnabled = false
                )
            }

            confirmed != null && blockedByStandby -> AnkleValuePresentation(
                angleText = AnkleProtocol.format(confirmed),
                angleContentDescription = historicalAngleDescription(confirmed),
                statusText = "Last verified ${AnkleProtocol.format(confirmed)}",
                historicalText = "Movement unavailable while standby is ${standby.displayName()}",
                isCurrentConfirmed = false,
                movementEnabled = false,
                minusEnabled = false,
                plusEnabled = false
            )

            confirmed != null -> AnkleValuePresentation(
                angleText = AnkleProtocol.format(confirmed),
                angleContentDescription = confirmedAngleDescription(confirmed),
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
                angleText = AnkleProtocol.format(lastVerified),
                angleContentDescription = historicalAngleDescription(lastVerified),
                statusText = "Last verified ${AnkleProtocol.format(lastVerified)}",
                historicalText = null,
                isCurrentConfirmed = false,
                movementEnabled = false,
                minusEnabled = false,
                plusEnabled = false
            )

            else -> AnkleValuePresentation(
                angleText = "Unknown",
                angleContentDescription = "Ankle angle unknown",
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

private fun confirmedAngleDescription(millidegrees: Int): String =
    "Confirmed ankle angle ${spokenAngle(millidegrees)}"

private fun historicalAngleDescription(millidegrees: Int): String =
    "Last verified ankle angle ${spokenAngle(millidegrees)}, current angle not confirmed"

private fun spokenAngle(millidegrees: Int): String {
    val magnitude = String.format(Locale.US, "%.1f", abs(millidegrees) / 1_000.0)
    val signed = when {
        millidegrees < 0 -> "minus $magnitude"
        millidegrees > 0 -> "plus $magnitude"
        else -> magnitude
    }
    return "$signed degrees"
}
