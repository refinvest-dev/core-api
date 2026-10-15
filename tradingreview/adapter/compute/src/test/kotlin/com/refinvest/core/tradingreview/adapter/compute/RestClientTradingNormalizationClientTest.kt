package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.ArtifactReadGrant
import com.refinvest.core.tradingreview.port.NormalizationSubmission
import com.refinvest.core.tradingreview.port.NormalizationGrantUpdate
import com.refinvest.core.tradingreview.port.NormalizationJobStatus
import org.yaml.snakeyaml.Yaml
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.MockHttpOutputMessage
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.time.Instant
import java.net.URI
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.JsonNode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                """{"versionSetHash":"${"c".repeat(64)}","normalizer":{"sourceDialectVersionsByArtifactRole":{"TRADE_HISTORY":"BINANCE_USDS_TRADE_HISTORY_V1","POSITION_HISTORY":"BINANCE_USDS_POSITION_HISTORY_V1"},"adapterVersion":"a1","normalizerVersion":"n1","canonicalSchemaVersion":"c1","implementationDigest":"digest-normalizer","versionSetHash":"${"d".repeat(64)}"},"reconstruction":{"instrumentTermsVersionByInstrument":{"BTC":"terms-v1"},"reconstructionPolicyVersion":"r1","reconciliationVersion":"rc1","reviewUnitSchemaVersion":"u1","evidenceSchemaVersion":"e1","implementationDigest":"digest-reconstruction","versionSetHash":"${"e".repeat(64)}"}}""",
                """[{"instrumentId":"BTC","venueSymbol":"BTCUSDT","settlementAsset":"USDT","quantityStep":"0.001","priceTick":"0.1","contractMultiplier":"1","settlementPrecision":8,"instrumentTermsVersion":"terms-v1"}]""",
                "TRADING_DATA_POLICY_V1", "retry-v1", "{}", 0)
            val identity = NormalizationInputIdentity.fromPinnedInput(input,
                mapper.readValue(input.versionSetJson, Map::class.java))
            val run = NormalizationRun("00000000-0000-0000-0000-000000000001", input,
                NormalizationInputCanonicalizer.hash(identity), "e".repeat(64),
                NormalizationRunStatus.DISPATCH_PENDING, 1,
                "00000000-0000-0000-0000-000000000003", null, null, now, now, null, now, null, 0,
                inputIdentity = identity)
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
            assertEquals("TRADE_HISTORY", first.path("artifactAccessGrants").path(0).path("role").asText())
            assertEquals(run.inputFingerprint, first.path("expectedInputSetHash").asText())
            assertEquals(first.path("attemptToken").asText(), second.path("attemptToken").asText())
            assertEquals(first.path("expectedInputSetHash").asText(), second.path("expectedInputSetHash").asText())
            assertEquals(first.path("inputIdentity"), second.path("inputIdentity"))
            assertEquals(identity.tradingBookId, first.path("inputIdentity").path("tradingBookId").asText())
            assertEquals(run.id, first.path("coreNormalizationRunId").asText())
            assertEquals("retry-v1", first.path("retryPolicyVersion").asText())
            assertEquals("d".repeat(64), first.path("inputIdentity").path("targetVersionSet")
                .path("normalizer").path("versionSetHash").asText())
            assertEquals("e".repeat(64), first.path("inputIdentity").path("targetVersionSet")
                .path("reconstruction").path("versionSetHash").asText())
            assertEquals(false, first.path("inputIdentity").toString().contains("https://private"))
            assertFalse(first.has("bookProduct"))
            assertFalse(first.has("expectedInputHash"))
            validateAgainstComputeOpenApi(first)
            assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertIs<NormalizationSubmission.Rejected>(
                client.submit(run, dispatch, grants("https://private/third/"))).code)
            assertEquals("INTERNAL_REQUEST_CONTRACT_INVALID", assertIs<NormalizationSubmission.Rejected>(
                client.submit(run, dispatch, grants("https://private/bad/").mapIndexed { index, grant ->
                    if (index == 0) grant.copy(rawSha256 = "0".repeat(64)) else grant
                })).code)
            assertEquals("INTERNAL_REQUEST_CONTRACT_INVALID", assertIs<NormalizationSubmission.Rejected>(
                client.submit(run.copy(inputIdentity = null), dispatch,
                    grants("https://private/legacy/"))).code)
            val status = NormalizationJobStatus("job-1", "PENDING", run.attemptToken, 0, 0,
                run.inputFingerprint, identity.targetVersionSet.versionSetHash,
                "REPLACE_QUEUED_GRANTS", now.plusSeconds(60), null)
            assertEquals("INTERNAL_REQUEST_CONTRACT_INVALID", assertIs<NormalizationGrantUpdate.Rejected>(
                client.updateGrants(run, dispatch, status, run.attemptToken,
                    grants("https://private/bad/").map { it.copy(coreArtifactId = "wrong") },
                    "update-key")).code)
        server.verify()
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateAgainstComputeOpenApi(request: JsonNode) {
        val spec = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "openapi/compute-api.yaml") }.first { it.isFile }
        val openApi = Yaml().load<Map<String, Any?>>(spec.inputStream())
        val schemas = (openApi["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        validateSchema(request, schemas.getValue("CreateTradingNormalizationJobRequest") as Map<String, Any?>,
            schemas)
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateSchema(value: JsonNode, schema: Map<String, Any?>,
                               schemas: Map<String, Any?>) {
        val reference = schema["\$ref"] as? String
        if (reference != null) {
            validateSchema(value, schemas.getValue(reference.substringAfterLast('/')) as Map<String, Any?>,
                schemas)
            return
        }
        when (schema["type"]) {
            "object" -> {
                assertTrue(value.isObject)
                val properties = schema["properties"] as? Map<String, Any?> ?: emptyMap()
                val required = schema["required"] as? List<String> ?: emptyList()
                required.forEach { assertTrue(value.has(it), "OpenAPI required field missing: " + it) }
                value.properties().forEach { (name, child) ->
                    val property = properties[name]
                    if (property == null) {
                        when (val extra = schema["additionalProperties"]) {
                            false -> error("OpenAPI forbids field: " + name)
                            is Map<*, *> -> validateSchema(child, extra as Map<String, Any?>, schemas)
                        }
                    } else validateSchema(child, property as Map<String, Any?>, schemas)
                }
            }
            "array" -> {
                assertTrue(value.isArray)
                (schema["minItems"] as? Number)?.let { assertTrue(value.size() >= it.toInt()) }
                (schema["maxItems"] as? Number)?.let { assertTrue(value.size() <= it.toInt()) }
                if (schema["uniqueItems"] == true)
                    assertEquals(value.size(), (0 until value.size()).map { value.path(it).toString() }.toSet().size)
                val itemSchema = schema["items"] as? Map<String, Any?>
                if (itemSchema != null) value.forEach { validateSchema(it, itemSchema, schemas) }
            }
            "string" -> {
                assertTrue(value.isTextual)
                (schema["minLength"] as? Number)?.let { assertTrue(value.asText().length >= it.toInt()) }
                (schema["pattern"] as? String)?.let { assertTrue(Regex(it).matches(value.asText())) }
                when (schema["format"]) {
                    "date-time" -> Instant.parse(value.asText())
                    "uri" -> assertTrue(URI(value.asText()).isAbsolute)
                }
            }
            "integer" -> {
                assertTrue(value.isIntegralNumber)
                (schema["minimum"] as? Number)?.let { assertTrue(value.asLong() >= it.toLong()) }
                (schema["maximum"] as? Number)?.let { assertTrue(value.asLong() <= it.toLong()) }
            }
        }
        (schema["enum"] as? List<*>)?.let { choices ->
            assertTrue(choices.any { it == value.asText() }, "OpenAPI enum mismatch")
        }
    }
}
