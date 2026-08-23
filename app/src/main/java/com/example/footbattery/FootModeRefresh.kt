package com.example.footbattery

enum class FootModeQueryFailure {
    WRITE_FAILED,
    RESPONSE_MISSING,
    INVALID_RESPONSE
}

data class FootModeQueryRead(
    val mode: FootMode,
    val value: FootModeValue?,
    val failure: FootModeQueryFailure?,
    val error: String?
)

interface FootModeRefreshTransport {
    suspend fun query(mode: FootMode): FootModeCommandExchangeResult
}

data class FootModesRefreshRead(val results: Map<FootMode, FootModeQueryRead>)

/** Two independent queries on one caller-owned session; no battery or ankle work is part of it. */
object FootModeRefresh {
    val queryOrder = listOf(FootMode.CHAIR_EXIT, FootMode.RELAX)

    suspend fun execute(
        transport: FootModeRefreshTransport,
        onResult: (FootModeQueryRead) -> Unit = {}
    ): FootModesRefreshRead {
        val results = linkedMapOf<FootMode, FootModeQueryRead>()
        queryOrder.forEach { mode ->
            val read = when (val result = transport.query(mode)) {
                is FootModeCommandExchangeResult.Response -> if (
                    FootModeProtocol.matches(
                        result.response,
                        mode,
                        FootModeResponseKind.QUERY
                    )
                ) {
                    FootModeQueryRead(mode, result.response.value, null, null)
                } else {
                    FootModeQueryRead(
                        mode,
                        null,
                        FootModeQueryFailure.INVALID_RESPONSE,
                        "${mode.displayName} response did not match"
                    )
                }
                is FootModeCommandExchangeResult.WriteFailed -> FootModeQueryRead(
                    mode,
                    null,
                    FootModeQueryFailure.WRITE_FAILED,
                    "Could not query ${mode.displayName}: ${result.message}"
                )
                is FootModeCommandExchangeResult.ResponseMissing -> FootModeQueryRead(
                    mode,
                    null,
                    FootModeQueryFailure.RESPONSE_MISSING,
                    "Could not verify ${mode.displayName}: ${result.message}"
                )
            }
            results[mode] = read
            onResult(read)
        }
        return FootModesRefreshRead(results)
    }
}
