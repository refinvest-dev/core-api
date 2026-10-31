package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.port.deletion.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant

/** Only the positive, fully correlated absence receipt is accepted as purge evidence. */
@Component
class RestClientTradingRuntimeDeletionClient private constructor(
    private val apiKey: String,
    private val mapper: ObjectMapper,
    private val client: RestClient,
) : TradingRuntimeDeletionClient {
    @Autowired
    constructor(@Value("\${refinvest.compute.base-url:http://localhost:8000}") baseUrl: String,
                @Value("\${REFINVEST_INTERNAL_API_KEY:}") apiKey: String,
                mapper: ObjectMapper) : this(apiKey, mapper, RestClient.builder().baseUrl(baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()).apply {
            setReadTimeout(Duration.ofSeconds(20))
        }).build())

    override fun purge(command: TradingRuntimeDeletion): TradingRuntimeDeletionOutcome {
        check(apiKey.isNotBlank()) { "Runtime deletion authentication unavailable" }
        return client.put().uri("/trading-review/normalization-runs/{runId}/deletion", command.runId.value)
            .header("X-Internal-Api-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("coreImportSessionId" to command.sessionId.value.toString(),
                "sourceDeletionGeneration" to command.sourceGeneration,
                "deletionRequestId" to command.requestId.value.toString(),
                "deletionGeneration" to command.deletionGeneration))
            .exchange { _, response ->
                check(response.statusCode.value() == 200) { "Runtime deletion dependency failure" }
                val body = mapper.readTree(response.body)
                check(body.path("coreNormalizationRunId").asText() == command.runId.value &&
                    body.path("coreImportSessionId").asText() == command.sessionId.value.toString() &&
                    body.path("sourceDeletionGeneration").isIntegralNumber &&
                    body.path("sourceDeletionGeneration").asLong() == command.sourceGeneration &&
                    body.path("deletionRequestId").asText() == command.requestId.value.toString() &&
                    body.path("deletionGeneration").isIntegralNumber &&
                    body.path("deletionGeneration").asLong() == command.deletionGeneration) {
                    "Runtime deletion receipt identity mismatch"
                }
                when (body.path("state").asText()) {
                    "ABSENT" -> {
                        check(body.path("retryAfterSeconds").isNull && body.path("verifiedAbsentAt").isString)
                        TradingRuntimeDeletionOutcome.Absent(Instant.parse(body.path("verifiedAbsentAt").asText()))
                    }
                    "PENDING" -> {
                        val retry = body.path("retryAfterSeconds")
                        check(body.path("verifiedAbsentAt").isNull && retry.isIntegralNumber && retry.asLong() in 1..60)
                        TradingRuntimeDeletionOutcome.Pending(Duration.ofSeconds(retry.asLong()))
                    }
                    else -> error("Runtime deletion receipt state invalid")
                }
            }
    }

    companion object {
        internal fun withClient(apiKey: String, mapper: ObjectMapper, client: RestClient) =
            RestClientTradingRuntimeDeletionClient(apiKey, mapper, client)
    }
}
