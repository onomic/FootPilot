package com.example.footbattery

sealed interface FootModeCommandExchangeResult {
    data class Response(val response: FootModeResponse) : FootModeCommandExchangeResult
    data class WriteFailed(val message: String) : FootModeCommandExchangeResult
    data class ResponseMissing(val message: String) : FootModeCommandExchangeResult
}

enum class FootModeTransactionFailure {
    INITIAL_QUERY_WRITE_FAILED,
    INITIAL_QUERY_RESPONSE_MISSING,
    INITIAL_QUERY_RESPONSE_INVALID,
    SET_WRITE_FAILED,
    FINAL_QUERY_WRITE_FAILED,
    FINAL_QUERY_RESPONSE_MISSING,
    FINAL_QUERY_RESPONSE_INVALID,
    FINAL_VALUE_MISMATCH;

    val retryable: Boolean get() = true
}

data class FootModeTransactionRead(
    val mode: FootMode,
    val requested: FootModeValue,
    val verified: Boolean,
    val finalValue: FootModeValue?,
    val ambiguous: Boolean,
    val setWriteAccepted: Boolean,
    val failure: FootModeTransactionFailure?,
    val error: String?
)

interface FootModeTransactionTransport {
    suspend fun exchange(
        mode: FootMode,
        command: ByteArray,
        expectedKind: FootModeResponseKind,
        expectedValue: FootModeValue? = null
    ): FootModeCommandExchangeResult
}

/** Fresh query, optional absolute SET, then an authoritative final query. */
object FootModeTransaction {
    suspend fun execute(
        mode: FootMode,
        requested: FootModeValue,
        transport: FootModeTransactionTransport
    ): FootModeTransactionRead {
        require(requested != FootModeValue.UNKNOWN) { "UNKNOWN cannot be used as a foot mode target" }

        val initial = when (val result = transport.exchange(
            mode,
            FootModeProtocol.queryCommand(mode),
            FootModeResponseKind.QUERY
        )) {
            is FootModeCommandExchangeResult.Response -> {
                if (!FootModeProtocol.matches(result.response, mode, FootModeResponseKind.QUERY)) {
                    return failure(
                        mode,
                        requested,
                        FootModeTransactionFailure.INITIAL_QUERY_RESPONSE_INVALID,
                        "${mode.displayName} query response did not match"
                    )
                }
                result.response.value
            }
            is FootModeCommandExchangeResult.WriteFailed -> return failure(
                mode,
                requested,
                FootModeTransactionFailure.INITIAL_QUERY_WRITE_FAILED,
                "Could not query ${mode.displayName}: ${result.message}"
            )
            is FootModeCommandExchangeResult.ResponseMissing -> return failure(
                mode,
                requested,
                FootModeTransactionFailure.INITIAL_QUERY_RESPONSE_MISSING,
                "Could not verify ${mode.displayName}: ${result.message}"
            )
        }

        if (initial == requested) {
            return FootModeTransactionRead(
                mode = mode,
                requested = requested,
                verified = true,
                finalValue = initial,
                ambiguous = false,
                setWriteAccepted = false,
                failure = null,
                error = null
            )
        }

        val setWriteAccepted = when (transport.exchange(
            mode,
            FootModeProtocol.setCommand(mode, requested),
            FootModeResponseKind.SET,
            requested
        )) {
            is FootModeCommandExchangeResult.Response,
            is FootModeCommandExchangeResult.ResponseMissing -> true
            is FootModeCommandExchangeResult.WriteFailed -> return failure(
                mode = mode,
                requested = requested,
                failure = FootModeTransactionFailure.SET_WRITE_FAILED,
                error = "${mode.displayName} command write failed",
                finalValue = initial
            )
        }

        return when (val finalResult = transport.exchange(
            mode,
            FootModeProtocol.queryCommand(mode),
            FootModeResponseKind.QUERY
        )) {
            is FootModeCommandExchangeResult.Response -> {
                if (!FootModeProtocol.matches(
                        finalResult.response,
                        mode,
                        FootModeResponseKind.QUERY
                    )
                ) {
                    finalQueryFailure(
                        mode,
                        requested,
                        setWriteAccepted,
                        FootModeTransactionFailure.FINAL_QUERY_RESPONSE_INVALID
                    )
                } else {
                    val finalValue = finalResult.response.value
                    val verified = finalValue == requested
                    FootModeTransactionRead(
                        mode = mode,
                        requested = requested,
                        verified = verified,
                        finalValue = finalValue,
                        ambiguous = false,
                        setWriteAccepted = setWriteAccepted,
                        failure = if (verified) null else {
                            FootModeTransactionFailure.FINAL_VALUE_MISMATCH
                        },
                        error = if (verified) null else {
                            "${mode.displayName} remained ${finalValue.displayName()}"
                        }
                    )
                }
            }
            is FootModeCommandExchangeResult.WriteFailed -> finalQueryFailure(
                mode,
                requested,
                setWriteAccepted,
                FootModeTransactionFailure.FINAL_QUERY_WRITE_FAILED
            )
            is FootModeCommandExchangeResult.ResponseMissing -> finalQueryFailure(
                mode,
                requested,
                setWriteAccepted,
                FootModeTransactionFailure.FINAL_QUERY_RESPONSE_MISSING
            )
        }
    }

    private fun failure(
        mode: FootMode,
        requested: FootModeValue,
        failure: FootModeTransactionFailure,
        error: String,
        finalValue: FootModeValue? = null
    ) = FootModeTransactionRead(
        mode = mode,
        requested = requested,
        verified = false,
        finalValue = finalValue,
        ambiguous = false,
        setWriteAccepted = false,
        failure = failure,
        error = error
    )

    private fun finalQueryFailure(
        mode: FootMode,
        requested: FootModeValue,
        setWriteAccepted: Boolean,
        failure: FootModeTransactionFailure
    ) = FootModeTransactionRead(
        mode = mode,
        requested = requested,
        verified = false,
        finalValue = null,
        ambiguous = setWriteAccepted,
        setWriteAccepted = setWriteAccepted,
        failure = failure,
        error = "${mode.displayName} not confirmed"
    )
}

fun FootModeValue.displayName(): String = when (this) {
    FootModeValue.ON -> "on"
    FootModeValue.OFF -> "off"
    FootModeValue.UNKNOWN -> "unknown"
}
