package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusLookup
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusValue
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RestClientComputeBacktestStatusClientTest {
    @Test
    fun `maps a completed Compute result with trades`() {
        val fixture = client(completedResponse())

        val lookup = fixture.client.getBacktestStatus("compute-run", BacktestRunId(42L))

        val status = assertIs<ComputeBacktestStatusLookup.Found>(lookup).status
        assertEquals(ComputeBacktestStatusValue.COMPLETED, status.status)
        assertEquals(BigDecimal("0.12"), status.result!!.metrics.totalReturn)
        assertEquals(1, status.result!!.metrics.tradeCount)
        assertEquals(1, status.result!!.trades.size)
        assertEquals(BigDecimal("24"), status.result!!.signalExecutionDelay.median)
        fixture.server.verify()
    }

    @Test
    fun `maps a failed Compute result with execution metadata`() {
        val fixture = client(
            """
            {
              "status":"FAILED",
              "actualPeriod":{"start":"2026-08-25","end":"2026-08-27"},
              "datasetSnapshotId":"snapshot-1",
              "engineVersion":"0.1.0",
              "failureReason":"PRICE_DATA_MISSING"
            }
            """.trimIndent(),
        )

        val lookup = fixture.client.getBacktestStatus("compute-run", BacktestRunId(42L))

        val status = assertIs<ComputeBacktestStatusLookup.Found>(lookup).status
        assertEquals(ComputeBacktestStatusValue.FAILED, status.status)
        assertEquals("PRICE_DATA_MISSING", status.failureReason)
        assertEquals("snapshot-1", status.datasetSnapshotId!!.value)
        fixture.server.verify()
    }

    private fun client(responseBody: String): ClientFixture {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()
        server.expect(requestTo("http://compute/backtests/compute-run"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Api-Key", "test-key"))
            .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON))

        return ClientFixture(
            client = RestClientComputeBacktestStatusClient(
                baseUrl = "http://compute",
                apiKey = "test-key",
                objectMapper = JsonMapper.builder()
                    .addModule(KotlinModule.Builder().build())
                    .build(),
                restClientBuilder = restClientBuilder,
            ),
            server = server,
        )
    }

    private data class ClientFixture(
        val client: RestClientComputeBacktestStatusClient,
        val server: MockRestServiceServer,
    )

    private fun completedResponse(): String =
        """
        {
          "status":"COMPLETED",
          "actualPeriod":{"start":"2026-08-25","end":"2026-08-27"},
          "datasetSnapshotId":"snapshot-1",
          "engineVersion":"0.1.0",
          "result":{
            "backtestRunId":"compute-run",
            "metrics":{
              "totalReturn":0.12,"cagr":0.08,"mdd":-0.03,"sharpe":1.2,"winRate":1,
              "tradeCount":1,"avgTradeReturn":0.12,"avgHoldingPeriod":2,"profitFactor":2
            },
            "equityCurve":[{"date":"2026-08-25","value":1.0}],
            "trades":[{
              "signalTime":"2026-08-25T00:00:00Z","entryTime":"2026-08-26T00:00:00Z",
              "entryPrice":100,"exitTime":"2026-08-27T00:00:00Z","exitPrice":112,
              "returnPct":0.12,"holdingPeriod":2
            }],
            "benchmark":{"primary":{"totalReturn":0.05,"cagr":0.03,"mdd":-0.01}},
            "signalExecutionDelay":{"median":24,"max":24,"distribution":[24]},
            "sampleSizeWarning":"LOW",
            "dataIntegrityStatus":{
              "datasetSnapshotId":"snapshot-1","corporateActionsApplied":true,
              "pointInTimeValidationPassed":true
            }
          }
        }
        """.trimIndent()
}
