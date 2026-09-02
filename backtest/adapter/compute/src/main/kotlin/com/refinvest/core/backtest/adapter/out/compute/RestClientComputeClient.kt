package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestRequest
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestSubmission
import com.refinvest.core.backtest.port.outbound.compute.ComputeClient
import com.refinvest.core.backtest.port.outbound.compute.LiteralOperandPayload
import com.refinvest.core.backtest.port.outbound.compute.MetricOperandPayload
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

@Component
class RestClientComputeClient(
    @Value("\${refinvest.compute.base-url:http://localhost:8000}") baseUrl: String,
    @Value("\${refinvest.compute.api-key:}") private val apiKey: String,
) : ComputeClient {
    private val restClient = RestClient.create(baseUrl)

    override fun requestBacktest(request: ComputeBacktestRequest): ComputeBacktestSubmission = try {
        restClient.post()
            .uri("/backtests")
            .header("X-Internal-Api-Key", apiKey)
            .header("Idempotency-Key", request.idempotencyKey.value.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(request.toBody())
            .exchange { _, response ->
                when (response.statusCode.value()) {
                    202 -> ComputeBacktestSubmission.Accepted(
                        requireNotNull(response.bodyTo(ComputeAcceptedResponse::class.java)).runId,
                    )
                    400, 409 -> ComputeBacktestSubmission.Rejected(
                        response.bodyTo(ComputeErrorResponse::class.java)?.message ?: "Compute rejected backtest request",
                    )
                    503 -> ComputeBacktestSubmission.RetryLater(RETRY_AFTER_SERVICE_UNAVAILABLE)
                    else -> ComputeBacktestSubmission.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
                }
            }
    } catch (_: Exception) {
        ComputeBacktestSubmission.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
    }

    private fun ComputeBacktestRequest.toBody(): Map<String, Any> = mapOf(
        "strategyVersionId" to strategyVersionId.value,
        "strategyVersion" to mapOf(
            "primarySignalAsset" to strategyVersion.primarySignalAsset,
            "conditions" to strategyVersion.conditions.map { condition ->
                mapOf(
                    "operator" to condition.operator,
                    "logicalCombinator" to condition.logicalCombinator,
                    "operandA" to condition.operandA.toBody(),
                    "operandB" to condition.operandB.toBody(),
                )
            },
            "executionAsset" to strategyVersion.executionAsset,
            "lag" to strategyVersion.lag,
            "exit" to mapOf("holdingSignalSessions" to strategyVersion.exit.holdingSignalSessions),
            "positionPolicy" to mapOf(
                "longOnly" to strategyVersion.positionPolicy.longOnly,
                "singlePosition" to strategyVersion.positionPolicy.singlePosition,
                "duplicateEntry" to strategyVersion.positionPolicy.duplicateEntry,
            ),
        ),
        "feeModel" to mapOf(
            "commission" to feeModel.commission.value,
            "slippage" to feeModel.slippage.value,
        ),
        "period" to mapOf("start" to period.start.toString(), "end" to period.end.toString()),
    ) + listOfNotNull(datasetSnapshotId?.let { "datasetSnapshotId" to it.value }).toMap()

    private fun com.refinvest.core.backtest.port.outbound.compute.MetricReferencePayload.toBody(): Map<String, Any?> = mapOf(
        "asset" to asset,
        "metric" to metric,
        "window" to window,
    )

    private fun com.refinvest.core.backtest.port.outbound.compute.ComputeConditionOperandPayload.toBody(): Any = when (this) {
        is LiteralOperandPayload -> value
        is MetricOperandPayload -> value.toBody()
    }

    private data class ComputeAcceptedResponse(val runId: String)
    private data class ComputeErrorResponse(val message: String?)

    private companion object {
        val RETRY_AFTER_SERVICE_UNAVAILABLE: Duration = Duration.ofSeconds(30)
        val RETRY_AFTER_TRANSIENT_FAILURE: Duration = Duration.ofSeconds(10)
    }
}
