package com.onomic.footpilot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FootModeRefreshTest {
    @Test fun oneTransportQueriesBothModesSequentiallyInSettingsOrder() = runBlocking {
        val transport = FakeRefreshTransport(
            mapOf(
                FootMode.CHAIR_EXIT to response(FootMode.CHAIR_EXIT, FootModeValue.ON),
                FootMode.RELAX to response(FootMode.RELAX, FootModeValue.OFF)
            )
        )
        val delivered = mutableListOf<FootModeQueryRead>()

        val result = FootModeRefresh.execute(transport, delivered::add)

        assertEquals(listOf(FootMode.CHAIR_EXIT, FootMode.RELAX), transport.queries)
        assertEquals(FootModeValue.ON, result.results[FootMode.CHAIR_EXIT]?.value)
        assertEquals(FootModeValue.OFF, result.results[FootMode.RELAX]?.value)
        assertEquals(FootMode.CHAIR_EXIT, delivered[0].mode)
        assertEquals(FootMode.RELAX, delivered[1].mode)
    }

    @Test fun oneModeFailureDoesNotPreventTheOtherQuery() = runBlocking {
        val transport = FakeRefreshTransport(
            mapOf(
                FootMode.CHAIR_EXIT to FootModeCommandExchangeResult.ResponseMissing("timed out"),
                FootMode.RELAX to response(FootMode.RELAX, FootModeValue.ON)
            )
        )

        val result = FootModeRefresh.execute(transport)

        assertEquals(2, transport.queries.size)
        assertNull(result.results[FootMode.CHAIR_EXIT]?.value)
        assertEquals(FootModeValue.ON, result.results[FootMode.RELAX]?.value)
    }

    @Test fun responseForOtherModeIsRejected() = runBlocking {
        val transport = FakeRefreshTransport(
            mapOf(
                FootMode.CHAIR_EXIT to response(FootMode.RELAX, FootModeValue.ON),
                FootMode.RELAX to response(FootMode.RELAX, FootModeValue.OFF)
            )
        )

        val result = FootModeRefresh.execute(transport)

        assertEquals(
            FootModeQueryFailure.INVALID_RESPONSE,
            result.results[FootMode.CHAIR_EXIT]?.failure
        )
    }

    private class FakeRefreshTransport(
        private val results: Map<FootMode, FootModeCommandExchangeResult>
    ) : FootModeRefreshTransport {
        val queries = mutableListOf<FootMode>()

        override suspend fun query(mode: FootMode): FootModeCommandExchangeResult {
            queries += mode
            return requireNotNull(results[mode])
        }
    }

    private fun response(mode: FootMode, value: FootModeValue) =
        FootModeCommandExchangeResult.Response(
            FootModeResponse(mode, FootModeResponseKind.QUERY, value)
        )
}
