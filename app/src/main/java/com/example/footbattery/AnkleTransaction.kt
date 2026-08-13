package com.example.footbattery

sealed interface AnkleCommandExchangeResult {
    data class Response(val response: AnkleResponse) : AnkleCommandExchangeResult
    data class WriteFailed(val message: String) : AnkleCommandExchangeResult
    data class ResponseMissing(val message: String) : AnkleCommandExchangeResult
}

interface AnkleTransactionTransport {
    suspend fun exchangeStandbyQuery(): StandbyCommandExchangeResult

    suspend fun exchangeAnkle(
        command: ByteArray,
        expectedKind: AnkleResponseKind,
        onWriteAccepted: () -> Unit = {}
    ): AnkleCommandExchangeResult
}

data class AnkleTransactionRead(
    val freshStandby: StandbyState?,
    val initialMd: Int?,
    val requestedMd: Int?,
    val finalConfirmedMd: Int?,
    val commandWriteAccepted: Boolean,
    val finalTruthConfirmed: Boolean,
    val requestSatisfied: Boolean,
    val unknownAfterCommand: Boolean,
    val error: String?
)

/** Fresh standby preflight, current-angle query, SET, and authoritative final query. */
object AnkleTransaction {
    suspend fun execute(
        request: AnkleTargetRequest,
        transport: AnkleTransactionTransport,
        onPotentialMovement: () -> Unit = {}
    ): AnkleTransactionRead {
        val absolute = (request as? AnkleTargetRequest.Absolute)?.targetMd
        if (absolute != null && !AnkleProtocol.isSupported(absolute)) {
            return failure(error = "Ankle target is outside -2.0° to +14.0°")
        }

        val standby = when (val result = transport.exchangeStandbyQuery()) {
            is StandbyCommandExchangeResult.Response -> result.response.state
            is StandbyCommandExchangeResult.WriteFailed -> return failure(
                error = "Could not verify standby: ${result.message}"
            )
            is StandbyCommandExchangeResult.ResponseMissing -> return failure(
                error = "Standby is unknown; Check now before adjusting ankle alignment"
            )
        }
        if (standby != StandbyState.OFF) {
            return failure(
                freshStandby = standby,
                error = "Turn standby off before adjusting ankle alignment"
            )
        }

        val initial = when (val result = transport.exchangeAnkle(
            AnkleProtocol.queryCommand(),
            AnkleResponseKind.QUERY
        )) {
            is AnkleCommandExchangeResult.Response -> result.response.millidegrees
                .takeIf(AnkleProtocol::isSupported)
                ?: return failure(
                    freshStandby = standby,
                    error = "Foot reported an unsupported ankle angle"
                )
            is AnkleCommandExchangeResult.WriteFailed -> return failure(
                freshStandby = standby,
                error = "Could not query ankle angle: ${result.message}"
            )
            is AnkleCommandExchangeResult.ResponseMissing -> return failure(
                freshStandby = standby,
                error = "Could not verify the current ankle angle"
            )
        }

        val target = when (request) {
            is AnkleTargetRequest.Absolute -> request.targetMd
            is AnkleTargetRequest.Fine -> AnkleProtocol.fineTarget(initial, request.adjustment)
                ?: return failure(
                    freshStandby = standby,
                    initialMd = initial,
                    finalConfirmedMd = initial,
                    finalTruthConfirmed = true,
                    error = "That fine adjustment would exceed the supported ankle range"
                )
        }

        var movementWriteAccepted = false
        fun markPotentialMovement() {
            if (!movementWriteAccepted) {
                movementWriteAccepted = true
                onPotentialMovement()
            }
        }
        val setResult = transport.exchangeAnkle(
            AnkleProtocol.setCommand(target),
            AnkleResponseKind.SET,
            onWriteAccepted = ::markPotentialMovement
        )
        val writeAccepted = when (setResult) {
            is AnkleCommandExchangeResult.Response,
            is AnkleCommandExchangeResult.ResponseMissing -> {
                markPotentialMovement()
                true
            }
            is AnkleCommandExchangeResult.WriteFailed -> {
                if (!movementWriteAccepted) {
                    return failure(
                        freshStandby = standby,
                        initialMd = initial,
                        requestedMd = target,
                        finalConfirmedMd = initial,
                        finalTruthConfirmed = true,
                        error = "Ankle command write failed: ${setResult.message}"
                    )
                }
                // Android accepted the write before its callback failed. Movement is possible,
                // so only a final foot query can restore authoritative state.
                true
            }
        }

        return when (val finalResult = transport.exchangeAnkle(
            AnkleProtocol.queryCommand(),
            AnkleResponseKind.QUERY
        )) {
            is AnkleCommandExchangeResult.Response -> {
                val confirmed = finalResult.response.millidegrees
                if (!AnkleProtocol.isSupported(confirmed)) {
                    unknownAfterCommand(standby, initial, target, writeAccepted)
                } else {
                    val differenceMd = kotlin.math.abs(confirmed.toLong() - target.toLong())
                    val matched = differenceMd <= CONFIRMATION_TOLERANCE_MD
                    AnkleTransactionRead(
                        freshStandby = standby,
                        initialMd = initial,
                        requestedMd = target,
                        finalConfirmedMd = confirmed,
                        commandWriteAccepted = writeAccepted,
                        finalTruthConfirmed = true,
                        requestSatisfied = matched,
                        unknownAfterCommand = false,
                        error = when {
                            confirmed == target -> null
                            matched -> "Ankle confirmed ${AnkleProtocol.format(confirmed)}"
                            else -> {
                                "Foot confirmed ${AnkleProtocol.format(confirmed)} instead of ${AnkleProtocol.format(target)}"
                            }
                        }
                    )
                }
            }
            is AnkleCommandExchangeResult.WriteFailed,
            is AnkleCommandExchangeResult.ResponseMissing ->
                unknownAfterCommand(standby, initial, target, writeAccepted)
        }
    }

    private fun unknownAfterCommand(
        standby: StandbyState,
        initialMd: Int,
        targetMd: Int,
        writeAccepted: Boolean
    ) = AnkleTransactionRead(
        freshStandby = standby,
        initialMd = initialMd,
        requestedMd = targetMd,
        finalConfirmedMd = null,
        commandWriteAccepted = writeAccepted,
        finalTruthConfirmed = false,
        requestSatisfied = false,
        unknownAfterCommand = true,
        error = "Ankle position is unknown after command; Check now to verify"
    )

    private fun failure(
        freshStandby: StandbyState? = null,
        initialMd: Int? = null,
        requestedMd: Int? = null,
        finalConfirmedMd: Int? = null,
        finalTruthConfirmed: Boolean = false,
        error: String
    ) = AnkleTransactionRead(
        freshStandby = freshStandby,
        initialMd = initialMd,
        requestedMd = requestedMd,
        finalConfirmedMd = finalConfirmedMd,
        commandWriteAccepted = false,
        finalTruthConfirmed = finalTruthConfirmed,
        requestSatisfied = false,
        unknownAfterCommand = false,
        error = error
    )

    private const val CONFIRMATION_TOLERANCE_MD = 1L
}
