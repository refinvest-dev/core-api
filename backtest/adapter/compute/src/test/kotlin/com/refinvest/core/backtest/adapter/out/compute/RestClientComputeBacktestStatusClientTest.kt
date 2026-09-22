package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.backtest.SignalExecutionMarketRelation
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class RestClientComputeBacktestStatusClientTest {
    @Test
    fun `maps a completed cross-market Compute result with benchmark curves`() {
        val fixture = client(completedResponse())

        val lookup = fixture.client.getBacktestStatus("compute-run", BacktestRunId(42L))

        val status = assertIs<ComputeBacktestStatusLookup.Found>(lookup).status
        assertEquals(ComputeBacktestStatusValue.COMPLETED, status.status)
        assertEquals(BigDecimal("0.12"), status.result!!.metrics.totalReturn)
        assertEquals(1, status.result!!.metrics.tradeCount)
        assertEquals(1, status.result!!.trades.size)
        assertEquals(SignalExecutionMarketRelation.CROSS_MARKET, status.result!!.signalExecutionMarketRelation)
        assertEquals("TQQQ", status.result!!.benchmark.primary.asset)
        assertEquals(2, status.result!!.benchmark.primary.equityCurve.size)
        assertEquals(null, status.result!!.benchmark.primary.cagr)
        assertEquals("QQQ", status.result!!.benchmark.secondaryReference!!.asset)
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
              "failureReason":"Price data is missing for the requested period.",
              "errorCode":"PRICE_DATA_MISSING"
            }
            """.trimIndent(),
        )

        val lookup = fixture.client.getBacktestStatus("compute-run", BacktestRunId(42L))

        val status = assertIs<ComputeBacktestStatusLookup.Found>(lookup).status
        assertEquals(ComputeBacktestStatusValue.FAILED, status.status)
        assertEquals("Price data is missing for the requested period.", status.failureReason)
        assertEquals("PRICE_DATA_MISSING", status.errorCode)
        assertEquals("snapshot-1", status.datasetSnapshotId!!.value)
        fixture.server.verify()
    }

    @Test
    fun `records poll HTTP error without a run id label`() {
        val registry = SimpleMeterRegistry()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://compute/backtests/compute-run"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError())
        val client = RestClientComputeBacktestStatusClient(
            "http://compute", "test-key",
            JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(), builder, registry,
        )

        assertIs<ComputeBacktestStatusLookup.RetryLater>(client.getBacktestStatus("compute-run", BacktestRunId(42L)))
        assertEquals(1L, registry.get("refinvest.core.compute.client")
            .tag("operation", "poll").tag("outcome", "http_error").timer().count())
        assertEquals(setOf("operation", "outcome"), registry.meters.single().id.tags.map { it.key }.toSet())
        server.verify()
    }

    @Test
    fun `maps a same-market relation`() {
        val fixture = client(completedResponse().replace("\"CROSS_MARKET\"", "\"SAME_MARKET\""))

        val lookup = fixture.client.getBacktestStatus("compute-run", BacktestRunId(42L))

        val status = assertIs<ComputeBacktestStatusLookup.Found>(lookup).status
        assertEquals(SignalExecutionMarketRelation.SAME_MARKET, status.result!!.signalExecutionMarketRelation)
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
            "benchmark":{
              "primary":{
                "asset":"TQQQ",
                "equityCurve":[{"date":"2026-08-25","value":1.0},{"date":"2026-08-27","value":1.05}],
                "totalReturn":0.05,"cagr":null,"mdd":-0.01
              },
              "secondaryReference":{
                "asset":"QQQ",
                "equityCurve":[{"date":"2026-08-25","value":1.0},{"date":"2026-08-27","value":1.02}],
                "totalReturn":0.02,"cagr":0.01,"mdd":-0.01
              }
            },
            "signalExecutionMarketRelation":"CROSS_MARKET",
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
