package com.example.footbattery

data class StandbyTransactionRead(
    val requested: StandbyState,
    val verified: Boolean,
    val finalState: StandbyState?,
    val batteryLevel: Int?,
    val ambiguous: Boolean,
    val error: String?,
    val batteryError: String? = null,
    val setWriteAccepted: Boolean = false
)

sealed interface StandbyCommandExchangeResult {
    data class Response(val response: StandbyResponse) : StandbyCommandExchangeResult

    /** The characteristic write did not complete successfully. */
    data class WriteFailed(val message: String) : StandbyCommandExchangeResult

    /** The characteristic write completed successfully, but no matching response was obtained. */
    data class ResponseMissing(val message: String) : StandbyCommandExchangeResult
}

sealed interface StandbyBatteryReadResult {
    data class Success(val level: Int) : StandbyBatteryReadResult
    data class Failed(val message: String) : StandbyBatteryReadResult
}

/** Android-free boundary used by the standby transaction and deterministic unit tests. */
interface StandbyTransactionTransport {
    suspend fun exchange(
        command: ByteArray,
        expectedKind: StandbyResponseKind,
        expectedState: StandbyState? = null
    ): StandbyCommandExchangeResult

    suspend fun readBattery(): StandbyBatteryReadResult
}

/**
 * An initial typed query that already matches is sufficient confirmation. Otherwise, set, issue a
 * typed final query, then read battery. A missing SET response is not treated as a rejected command
 * because the write callback has already succeeded by that point.
 */
object StandbyTransaction {
    suspend fun execute(
        requested: StandbyState,
        transport: StandbyTransactionTransport
    ): StandbyTransactionRead {
        require(requested != StandbyState.UNKNOWN)

        val initial = when (val result = transport.exchange(
            StandbyProtocol.queryCommand(),
            StandbyResponseKind.QUERY
        )) {
            is StandbyCommandExchangeResult.Response -> result.response.state
            is StandbyCommandExchangeResult.WriteFailed -> return failure(
                requested,
                "Could not verify standby: ${result.message}"
            )
            is StandbyCommandExchangeResult.ResponseMissing -> return failure(
                requested,
                "Could not verify standby: ${result.message}"
            )
        }

        return executeFromInitial(requested, initial, transport)
    }

    /** Notification toggle derives its target from a fresh foot response, never rendered state. */
    suspend fun executeToggle(
        transport: StandbyTransactionTransport
    ): StandbyTransactionRead {
        val initial = when (val result = transport.exchange(
            StandbyProtocol.queryCommand(),
            StandbyResponseKind.QUERY
        )) {
            is StandbyCommandExchangeResult.Response -> result.response.state
            is StandbyCommandExchangeResult.WriteFailed -> return failure(
                StandbyState.UNKNOWN,
                "Could not verify standby: ${result.message}"
            )
            is StandbyCommandExchangeResult.ResponseMissing -> return failure(
                StandbyState.UNKNOWN,
                "Could not verify standby: ${result.message}"
            )
        }
        val requested = when (initial) {
            StandbyState.ON -> StandbyState.OFF
            StandbyState.OFF -> StandbyState.ON
            StandbyState.UNKNOWN -> return failure(
                StandbyState.UNKNOWN,
                "Could not verify standby"
            )
        }
        return executeFromInitial(requested, initial, transport)
    }

    private suspend fun executeFromInitial(
        requested: StandbyState,
        initial: StandbyState,
        transport: StandbyTransactionTransport
    ): StandbyTransactionRead {

        var setWriteAccepted = false
        val finalState = if (initial == requested) {
            initial
        } else {
            when (val result = transport.exchange(
                StandbyProtocol.setCommand(requested),
                StandbyResponseKind.SET,
                requested
            )) {
                is StandbyCommandExchangeResult.Response -> setWriteAccepted = true
                is StandbyCommandExchangeResult.ResponseMissing -> setWriteAccepted = true
                is StandbyCommandExchangeResult.WriteFailed -> return failure(
                    requested = requested,
                    error = "Standby command write failed: ${result.message}"
                )
            }

            val finalResult = transport.exchange(
                StandbyProtocol.queryCommand(),
                StandbyResponseKind.QUERY
            )
            when (finalResult) {
                is StandbyCommandExchangeResult.Response -> finalResult.response.state
                is StandbyCommandExchangeResult.WriteFailed -> return finalQueryFailure(
                    requested,
                    setWriteAccepted,
                    finalResult.message
                )
                is StandbyCommandExchangeResult.ResponseMissing -> return finalQueryFailure(
                    requested,
                    setWriteAccepted,
                    finalResult.message
                )
            }
        }

        val battery = transport.readBattery()
        val batteryLevel = (battery as? StandbyBatteryReadResult.Success)?.level
        val batteryError = (battery as? StandbyBatteryReadResult.Failed)?.message
        val verified = finalState == requested
        return StandbyTransactionRead(
            requested = requested,
            verified = verified,
            finalState = finalState,
            batteryLevel = batteryLevel,
            ambiguous = false,
            error = if (verified) null else "Foot remained standby ${finalState.displayName()}",
            batteryError = batteryError,
            setWriteAccepted = setWriteAccepted
        )
    }

    private fun failure(
        requested: StandbyState,
        error: String
    ) = StandbyTransactionRead(
        requested = requested,
        verified = false,
        finalState = null,
        batteryLevel = null,
        ambiguous = false,
        error = error
    )

    private fun finalQueryFailure(
        requested: StandbyState,
        setWriteAccepted: Boolean,
        detail: String
    ) = StandbyTransactionRead(
        requested = requested,
        verified = false,
        finalState = null,
        batteryLevel = null,
        ambiguous = setWriteAccepted,
        error = if (setWriteAccepted) {
            "Standby not confirmed: state could not be verified after command"
        } else {
            "Final standby verification failed: $detail"
        },
        setWriteAccepted = setWriteAccepted
    )
}
