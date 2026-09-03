package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestRequest
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestSubmission
import com.refinvest.core.backtest.port.outbound.compute.ComputeConditionPayload
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKey
import com.refinvest.core.backtest.port.outbound.compute.LiteralOperandPayload
import com.refinvest.core.backtest.port.outbound.compute.MetricReferencePayload
import com.refinvest.core.backtest.port.outbound.compute.PositionPolicyPayload
import com.refinvest.core.backtest.port.outbound.compute.StrategyVersionPayload
import com.refinvest.core.backtest.port.outbound.compute.TimeBasedExitPayload
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted
import org.springframework.web.client.RestClient

class RestClientComputeClientTest {
    @Test
    fun `serializes strategy version snowflake id as Compute contract string`() {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()
        server.expect(requestTo("http://compute/backtests"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Api-Key", "test-key"))
            .andExpect(content().json("""{"strategyVersionId":"123"}""", JsonCompareMode.LENIENT))
            .andRespond(
                withAccepted()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"runId":"compute-run","strategyVersionId":"123","status":"PENDING"}"""),
            )

        val client = RestClientComputeClient("http://compute", "test-key", restClientBuilder)

        val submission = client.requestBacktest(testRequest())

        assertIs<ComputeBacktestSubmission.Accepted>(submission)
        assertEquals("compute-run", submission.computeRunId)
        server.verify()
    }

    private fun testRequest(): ComputeBacktestRequest = ComputeBacktestRequest(
        idempotencyKey = ComputeIdempotencyKey(UUID.randomUUID()),
        strategyVersionId = StrategyVersionId(123),
        strategyVersion = StrategyVersionPayload(
            primarySignalAsset = "QQQ",
            conditions = listOf(
                ComputeConditionPayload(
                    operator = "GT",
                    logicalCombinator = null,
                    operandA = MetricReferencePayload("QQQ", "SIMPLE", null),
                    operandB = LiteralOperandPayload(0.0),
                ),
            ),
            executionAsset = "QQQ",
            lag = 1,
            exit = TimeBasedExitPayload(1),
            positionPolicy = PositionPolicyPayload(true, true, "IGNORE"),
        ),
        feeModel = FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
        period = Period(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 1)),
    )
}
