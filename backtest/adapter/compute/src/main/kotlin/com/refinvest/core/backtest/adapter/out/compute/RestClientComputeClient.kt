package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestRequest
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestSubmission
import com.refinvest.core.backtest.port.outbound.compute.ComputeClient
import com.refinvest.core.backtest.port.outbound.compute.LiteralOperandPayload
import com.refinvest.core.backtest.port.outbound.compute.MetricOperandPayload
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Component
class RestClientComputeClient private constructor(
    private val restClient: RestClient,
    private val apiKey: String,
) : ComputeClient {
    @Autowired
    constructor(
        @Value("\${refinvest.compute.base-url:http://localhost:8000}") baseUrl: String,
        @Value("\${refinvest.compute.api-key:}") apiKey: String,
    ) : this(createRestClient(baseUrl), apiKey)

    internal constructor(
        baseUrl: String,
        apiKey: String,
        restClientBuilder: RestClient.Builder,
    ) : this(restClientBuilder.baseUrl(baseUrl).build(), apiKey)

    override fun requestBacktest(request: ComputeBacktestRequest): ComputeBacktestSubmission = try {
        restClient.post()
            .uri("/backtests")
            .header("X-Internal-Api-Key", apiKey)
            .header("Idempotency-Key", request.idempotencyKey.value.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(request.toBody())
            .exchange { _, response ->
                when (response.statusCode.value()) {
                    202 -> response.bodyTo(String::class.java)
                        ?.computeRunId()
                        ?.let(ComputeBacktestSubmission::Accepted)
                        ?: ComputeBacktestSubmission.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
                    400, 409 -> response.bodyTo(String::class.java)
                        ?.takeIf(String::isNotBlank)
                        ?.let { body -> ComputeBacktestSubmission.Rejected(body, body.errorCode()) }
                        ?: ComputeBacktestSubmission.Rejected("Compute rejected backtest request")
                    503 -> ComputeBacktestSubmission.RetryLater(RETRY_AFTER_SERVICE_UNAVAILABLE)
                    else -> ComputeBacktestSubmission.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
                }
            }
    } catch (exception: Exception) {
        logger.warn("Compute backtest submission failed; scheduling a retry.", exception)
        ComputeBacktestSubmission.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
    }

    private fun ComputeBacktestRequest.toBody(): Map<String, Any> = mapOf(
        "strategyVersionId" to strategyVersionId.value.toString(),
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

    private fun String.computeRunId(): String? = RUN_ID_PATTERN.find(this)?.groupValues?.get(1)

    private fun String.errorCode(): String? = ERROR_CODE_PATTERN.find(this)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

    private companion object {
        val logger = LoggerFactory.getLogger(RestClientComputeClient::class.java)
        val RUN_ID_PATTERN = Regex("\\\"runId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val ERROR_CODE_PATTERN = Regex("\\\"errorCode\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val RETRY_AFTER_SERVICE_UNAVAILABLE: Duration = Duration.ofSeconds(30)
        val RETRY_AFTER_TRANSIENT_FAILURE: Duration = Duration.ofSeconds(10)

        fun createRestClient(baseUrl: String): RestClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                ),
            )
            .build()
    }
}
