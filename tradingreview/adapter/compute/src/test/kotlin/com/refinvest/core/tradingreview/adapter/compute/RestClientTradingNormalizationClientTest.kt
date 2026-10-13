package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.ArtifactReadGrant
import com.refinvest.core.tradingreview.port.NormalizationSubmission
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.MockHttpOutputMessage
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import tools.jackson.databind.json.JsonMapper

class RestClientTradingNormalizationClientTest {
    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun retryUsesSameComputeKeyAndJobDespiteFreshGrantUris() {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String?, String>>())
        val dispatchId = "00000000-0000-0000-0000-000000000002"
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.times(2), requestTo("http://compute.test/trading-review/normalization-jobs"))
            .andExpect { request -> requests.add(request.headers.getFirst("Idempotency-Key") to
                (request as MockHttpOutputMessage).bodyAsString) }
            .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                .body("""{"jobId":"job-1","coreLogicalJobId":"$dispatchId"}"""))
        server.expect(requestTo("http://compute.test/trading-review/normalization-jobs"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("""{"code":"IDEMPOTENCY_PAYLOAD_CONFLICT"}"""))
        val client = RestClientTradingNormalizationClient.withClient("test-internal-key", mapper, builder.build())
            val input = NormalizationInput(TradingBookId(1), TradingImportSessionId(2), 3,
                listOf(NormalizationArtifactInput(TradingSourceArtifactId(10), TradingArtifactRole.TRADE_HISTORY,
                    "a".repeat(64), 100, "text/csv", "BINANCE_USDS_TRADE_HISTORY_V1"),
                    NormalizationArtifactInput(TradingSourceArtifactId(11), TradingArtifactRole.POSITION_HISTORY,
                        "b".repeat(64), 200, "text/csv", "BINANCE_USDS_POSITION_HISTORY_V1")),
                "BINANCE", "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC",
                """{"versionSetHash":"${"c".repeat(64)}","normalizer":{"sourceDialectVersionsByArtifactRole":{"TRADE_HISTORY":"BINANCE_USDS_TRADE_HISTORY_V1","POSITION_HISTORY":"BINANCE_USDS_POSITION_HISTORY_V1"},"adapterVersion":"a1","normalizerVersion":"n1","canonicalSchemaVersion":"c1","implementationDigest":"digest"},"reconstruction":{"instrumentTermsVersionByInstrument":{"BTC":"terms-v1"},"reconstructionPolicyVersion":"r1","reconciliationVersion":"rc1","reviewUnitSchemaVersion":"u1","evidenceSchemaVersion":"e1","implementationDigest":"digest"}}""",
                """[{"instrumentId":"BTC","venueSymbol":"BTCUSDT","settlementAsset":"USDT","quantityStep":"0.001","priceTick":"0.1","contractMultiplier":"1","settlementPrecision":8,"instrumentTermsVersion":"terms-v1"}]""",
                "TRADING_DATA_POLICY_V1", "retry-v1", "{}", 0)
            val run = NormalizationRun("00000000-0000-0000-0000-000000000001", input,
                "d".repeat(64), "e".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1,
                "00000000-0000-0000-0000-000000000003", null, null, now, now, null, now, null, 0)
            val dispatch = NormalizationDispatch(dispatchId, run.id, "f".repeat(64),
                NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
            fun grants(uri: String) = input.artifacts.reversed().map {
                ArtifactReadGrant(it.artifactId.value.toString(), it.role.name, it.sha256,
                    it.contentLength, it.mediaType, uri + it.artifactId.value, now.plusSeconds(60))
            }
            assertEquals("job-1", assertIs<NormalizationSubmission.Accepted>(
                client.submit(run, dispatch, grants("https://private/first/"))).jobId)
            assertEquals("job-1", assertIs<NormalizationSubmission.Accepted>(
                client.submit(run, dispatch, grants("https://private/second/"))).jobId)
            assertEquals(listOf(dispatch.computeKey, dispatch.computeKey), requests.map { it.first })
            val first = mapper.readTree(requests[0].second)
            val second = mapper.readTree(requests[1].second)
            assertEquals("TRADE_HISTORY", first.path("artifacts").path(0).path("role").asText())
            assertEquals(run.inputFingerprint, first.path("expectedInputHash").asText())
            assertEquals(first.path("attemptToken").asText(), second.path("attemptToken").asText())
            assertEquals(first.path("expectedInputHash").asText(), second.path("expectedInputHash").asText())
            assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertIs<NormalizationSubmission.Rejected>(
                client.submit(run, dispatch, grants("https://private/third/"))).code)
        server.verify()
    }
}
