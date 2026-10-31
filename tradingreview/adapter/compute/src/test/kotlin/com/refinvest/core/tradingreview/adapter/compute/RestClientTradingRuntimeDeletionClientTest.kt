package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.deletion.*
import java.time.Instant
import kotlin.test.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

class RestClientTradingRuntimeDeletionClientTest {
    private val command = TradingRuntimeDeletion(NormalizationRunId("10000000-0000-0000-0000-000000000001"),
        TradingImportSessionId(12), 7, DeletionRequestId(42), 1)
    private val mapper = JsonMapper.builder().build()

    @Test
    fun acceptsCorrelatedPendingAndActualAbsenceWithIndependentGenerations() {
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        for (state in listOf("PENDING", "ABSENT")) {
            server.expect(requestTo("http://compute.test/trading-review/normalization-runs/${command.runId.value}/deletion"))
                .andExpect(method(org.springframework.http.HttpMethod.PUT))
                .andExpect(content().json("""{"coreImportSessionId":"12","sourceDeletionGeneration":7,
                    "deletionRequestId":"42","deletionGeneration":1}"""))
                .andRespond(withSuccess(body(state), MediaType.APPLICATION_JSON))
        }
        val client = RestClientTradingRuntimeDeletionClient.withClient("key", mapper, builder.build())
        assertEquals(5, assertIs<TradingRuntimeDeletionOutcome.Pending>(client.purge(command)).retryAfter.seconds)
        assertEquals(Instant.parse("2026-10-31T00:00:00Z"),
            assertIs<TradingRuntimeDeletionOutcome.Absent>(client.purge(command)).verifiedAt)
        server.verify()
    }

    @Test
    fun rejectsMissingAndMismatchedEvidenceInsteadOfCompletingDeletion() {
        val invalid = listOf(body("ABSENT").replace("\"42\"", "\"43\""),
            body("ABSENT").replace("Generation\":7", "Generation\":8"),
            body("ABSENT").replace("Generation\":1", "Generation\":2"),
            body("ABSENT").replace(command.runId.value, "other-run"),
            body("ABSENT").replace("\"12\"", "\"13\""),
            body("ABSENT").replace("null", "5"),
            body("PENDING").replace("Seconds\":5", "Seconds\":0"))
        for (response in invalid) {
            val builder = RestClient.builder().baseUrl("http://compute.test")
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
            val client = RestClientTradingRuntimeDeletionClient.withClient("key", mapper, builder.build())
            assertFailsWith<IllegalStateException> { client.purge(command) }
            server.verify()
        }
        for (status in listOf(HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE)) {
            val builder = RestClient.builder().baseUrl("http://compute.test")
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(anything()).andRespond(withStatus(status))
            assertFailsWith<IllegalStateException> {
                RestClientTradingRuntimeDeletionClient.withClient("key", mapper, builder.build()).purge(command)
            }
            server.verify()
        }
    }

    private fun body(state: String) = """{"coreNormalizationRunId":"${command.runId.value}",
        "coreImportSessionId":"12","sourceDeletionGeneration":7,"deletionRequestId":"42",
        "deletionGeneration":1,"state":"$state",
        "verifiedAbsentAt":${if (state == "ABSENT") "\"2026-10-31T00:00:00Z\"" else "null"},
        "retryAfterSeconds":${if (state == "ABSENT") "null" else "5"}}"""
}
