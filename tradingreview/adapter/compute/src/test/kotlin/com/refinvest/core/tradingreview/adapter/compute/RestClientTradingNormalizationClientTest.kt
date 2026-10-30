package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationInputIdentity
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationDispatchStatus
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunStatus
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.ArtifactReadGrant
import com.refinvest.core.tradingreview.port.NormalizationAckOutcome
import com.refinvest.core.tradingreview.port.NormalizationGrantUpdate
import com.refinvest.core.tradingreview.port.NormalizationJobStatus
import com.refinvest.core.tradingreview.port.NormalizationPayloadAcknowledgement
import com.refinvest.core.tradingreview.port.NormalizationPayloadLookup
import com.refinvest.core.tradingreview.port.NormalizationResultLookup
import com.refinvest.core.tradingreview.port.NormalizationStatusLookup
import com.refinvest.core.tradingreview.port.NormalizationSubmission
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.MockHttpOutputMessage
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class RestClientTradingNormalizationClientTest {
    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val deliveryPolicy = NormalizationResultDeliveryPolicy(
        "RESULT_DELIVERY_V1", "SINGLE_RESOURCE", 52_428_800, listOf("identity"))

    @Test
    fun orphanSweepRemovesOnlyExpiredOpaqueSpoolFiles(@TempDir directory: Path) {
        val stale = Files.createFile(directory.resolve("payload-stale.spool"))
        val fresh = Files.createFile(directory.resolve("payload-fresh.spool"))
        val unrelated = Files.createFile(directory.resolve("other-file"))
        Files.setLastModifiedTime(stale, FileTime.from(now.minusSeconds(25 * 3600)))
        Files.setLastModifiedTime(unrelated, FileTime.from(now.minusSeconds(25 * 3600)))
        assertEquals(1, NormalizationResultSpool.cleanupOlderThan(directory, now.minusSeconds(24 * 3600)))
        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(fresh))
        assertTrue(Files.exists(unrelated))
    }

    @Test
    fun terminalPayloadOverContractLimitIsRejectedBeforeParsing() {
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://compute.test/trading-review/terminal-payloads/oversized"))
            .andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.parseMediaType("application/vnd.refinvest.trading-normalization+json"))
                .header("Content-Length", "52428801").body("{}"))
        val client = RestClientTradingNormalizationClient.withClient("test-internal-key", mapper, builder.build())
        val descriptor = testDescriptor("oversized", 100)
        assertEquals("RESULT_PAYLOAD_LIMIT_EXCEEDED",
            assertIs<NormalizationPayloadLookup.Rejected>(client.download(descriptor, deliveryPolicy)).code)
        server.verify()
    }

    @Test
    fun acceptedAndStatusResultPairMustMatchRunPin() {
        val input = NormalizationInput(TradingBookId(1), TradingImportSessionId(2), 3,
            listOf(NormalizationArtifactInput(TradingSourceArtifactId(10), TradingArtifactRole.TRADE_HISTORY,
                "a".repeat(64), 100, "text/csv", "AUTO"),
                NormalizationArtifactInput(TradingSourceArtifactId(11), TradingArtifactRole.POSITION_HISTORY,
                    "b".repeat(64), 200, "text/csv", "AUTO")),
            "BINANCE", "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC",
            """{"versionSetHash":"${"f".repeat(64)}","normalizer":{"sourceDialectVersionsByArtifactRole":{"TRADE_HISTORY":"AUTO","POSITION_HISTORY":"AUTO"},"adapterVersion":"a","normalizerVersion":"n","canonicalSchemaVersion":"c","implementationDigest":"d","versionSetHash":"${"d".repeat(64)}"},"reconstruction":{"instrumentTermsVersionByInstrument":{},"reconstructionPolicyVersion":"r","reconciliationVersion":"rc","reviewUnitSchemaVersion":"u","evidenceSchemaVersion":"e","implementationDigest":"d","versionSetHash":"${"e".repeat(64)}"}}""",
            "[]", "TRADING_DATA_POLICY_V1", "retry-v1", "{}", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            mapper.readValue(input.versionSetJson, Map::class.java))
        val run = NormalizationRun.restore(NormalizationRunId("00000000-0000-0000-0000-000000000001"), input,
            NormalizationInputCanonicalizer.hash(identity), "a".repeat(64),
            NormalizationRunStatus.SUBMITTED, 1, "00000000-0000-0000-0000-000000000003",
            "job-1", null, now, now, null, now, null, 0,
            inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch("00000000-0000-0000-0000-000000000002", run.id.value,
            "b".repeat(64), NormalizationDispatchStatus.SUBMITTED, 0, now, null, null, null, now, now)
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://compute.test/trading-review/normalization-jobs"))
            .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(mapOf(
                    "jobId" to "job-1", "coreLogicalJobId" to dispatch.id,
                    "resultSchemaVersion" to "TRADING_NORMALIZATION_RESULT_V2",
                    "resultHashSpecificationVersion" to "TRADING_NORMALIZATION_RESULT_HASH_V2"))))
        server.expect(requestTo("http://compute.test/trading-review/normalization-jobs/job-1"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(mapOf(
                    "jobId" to "job-1", "coreLogicalJobId" to dispatch.id,
                    "coreNormalizationRunId" to run.id.value,
                    "hashSpecificationVersion" to identity.hashSpecificationVersion,
                    "targetVersionSet" to identity.targetVersionSet.toWireValue(),
                    "resultSchemaVersion" to "TRADING_NORMALIZATION_RESULT_V2",
                    "resultHashSpecificationVersion" to "TRADING_NORMALIZATION_RESULT_HASH_V2"))))
        for ((status, body) in listOf(
            HttpStatus.SERVICE_UNAVAILABLE to """{"code":"TRADING_REVIEW_CAPACITY_EXCEEDED"}""",
            HttpStatus.SERVICE_UNAVAILABLE to "{}",
            HttpStatus.BAD_GATEWAY to "{}",
            HttpStatus.TOO_MANY_REQUESTS to "{}")) {
            server.expect(requestTo("http://compute.test/trading-review/normalization-jobs"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                    .header("Retry-After", "1").body(body))
        }
        val client = RestClientTradingNormalizationClient.withClient("test-internal-key", mapper, builder.build())
        val grants = input.artifacts.map { artifact -> ArtifactReadGrant(
            artifact.artifactId.value.toString(), artifact.role.name, artifact.sha256,
            artifact.contentLength, artifact.mediaType, "https://private/${artifact.artifactId.value}",
            now.plusSeconds(60)) }
        assertEquals("RESULT_VERSION_PIN_MISMATCH", assertIs<NormalizationSubmission.Rejected>(
            client.submit(run, dispatch, grants)).code)
        assertEquals("RESULT_VERSION_PIN_MISMATCH", assertIs<NormalizationStatusLookup.Rejected>(
            client.status(run, dispatch)).code)
        for (unknown in listOf(false, true, true, true)) {
            assertEquals(unknown, assertIs<NormalizationSubmission.Retry>(
                client.submit(run, dispatch, grants)).outcomeUnknown)
        }
        server.verify()
    }

    @Test
    fun terminalPayloadRejectsHeaderLengthMismatchAndUnsupportedEncoding() {
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val mediaType = MediaType.parseMediaType("application/vnd.refinvest.trading-normalization+json")
        server.expect(requestTo("http://compute.test/trading-review/terminal-payloads/wrong-length"))
            .andRespond(withStatus(HttpStatus.OK).contentType(mediaType)
                .header("Content-Length", "3").body("{}"))
        server.expect(requestTo("http://compute.test/trading-review/terminal-payloads/compressed"))
            .andRespond(withStatus(HttpStatus.OK).contentType(mediaType)
                .header("Content-Encoding", "gzip").body("{}"))
        val client = RestClientTradingNormalizationClient.withClient("test-internal-key", mapper, builder.build())
        assertEquals("RESULT_PAYLOAD_LENGTH_MISMATCH", assertIs<NormalizationPayloadLookup.Rejected>(
            client.download(testDescriptor("wrong-length", 2), deliveryPolicy)).code)
        assertEquals("RESULT_CONTENT_ENCODING_UNSUPPORTED", assertIs<NormalizationPayloadLookup.Rejected>(
            client.download(testDescriptor("compressed", 2), deliveryPolicy)).code)
        server.verify()
    }

    @Test
    fun terminalPayloadRejectsTruncatedBodyWithoutContentLength() {
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://compute.test/trading-review/terminal-payloads/truncated"))
            .andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.parseMediaType("application/vnd.refinvest.trading-normalization+json"))
                .body("{}"))
        val client = RestClientTradingNormalizationClient.withClient("test-internal-key", mapper, builder.build())
        assertEquals("RESULT_PAYLOAD_LENGTH_MISMATCH", assertIs<NormalizationPayloadLookup.Rejected>(
            client.download(testDescriptor("truncated", 3), deliveryPolicy)).code)
        server.verify()
    }

    @Test
    fun terminalPayloadStopsAtActualByteLimitWithoutContentLength() {
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://compute.test/trading-review/terminal-payloads/actual-over-limit"))
            .andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.parseMediaType("application/vnd.refinvest.trading-normalization+json"))
                .body("{}XY"))
        val client = RestClientTradingNormalizationClient.withClient("test-internal-key", mapper, builder.build())
        val smallPolicy = deliveryPolicy.copy(maxAcceptedPayloadBytes = 3)
        assertEquals("RESULT_PAYLOAD_LIMIT_EXCEEDED", assertIs<NormalizationPayloadLookup.Rejected>(
            client.download(testDescriptor("actual-over-limit", 3), smallPolicy)).code)
        server.verify()
    }

    @Test
    fun terminalResourceUsesExactEntityBytesAndAckHasStableIdentity() {
        val builder = RestClient.builder().baseUrl("http://compute.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val bytes = """{"synthetic":"테스트","value":"1.2300"}""".toByteArray(Charsets.UTF_8)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        server.expect(requestTo("http://compute.test/trading-review/normalization-jobs/job-1/result"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                {"jobId":"job-1","coreLogicalJobId":"logical-1","coreNormalizationRunId":"run-1",
                 "payloadId":"payload-1","transportMode":"SINGLE_RESOURCE",
                 "resultDeliveryPolicyVersion":"RESULT_DELIVERY_V1","contentEncoding":"identity",
                 "mediaType":"application/vnd.refinvest.trading-normalization+json",
                 "byteSize":${bytes.size},"payloadSha256":"$hash","attemptToken":"attempt-1",
                 "deletionGeneration":0,"inputSetHash":"${"a".repeat(64)}",
                 "hashSpecificationVersion":"TRADING_INPUT_SET_HASH_V1","targetVersionSet":{},
                 "resultSchemaVersion":"TRADING_NORMALIZATION_RESULT_V1",
                 "resultHashSpecificationVersion":"TRADING_NORMALIZATION_RESULT_HASH_V1",
                 "counts":{},"sectionDigests":[],"reconciliationManifestHash":"${"b".repeat(64)}",
                 "resultHash":"${"c".repeat(64)}","expiresAt":"2026-01-01T01:00:00Z"}
            """))
        server.expect(requestTo("http://compute.test/trading-review/terminal-payloads/payload-1"))
            .andExpect { request -> assertEquals("identity", request.headers.getFirst("Accept-Encoding")) }
            .andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.parseMediaType("application/vnd.refinvest.trading-normalization+json;charset=utf-8"))
                .body(String(bytes, Charsets.UTF_8)))
        server.expect(requestTo("http://compute.test/trading-review/jobs/job-1/acknowledgement"))
            .andExpect { request ->
                assertEquals("stable-ack-key", request.headers.getFirst("Idempotency-Key"))
                val body = mapper.readTree((request as MockHttpOutputMessage).bodyAsString)
                assertEquals("session-1", body.path("coreAggregateId").asText())
                assertEquals("PERSISTED", body.path("disposition").asText())
                assertEquals("payload-1", body.path("payloadId").asText())
                assertFalse(body.has("currentDeletionGeneration"))
            }.andRespond(withStatus(HttpStatus.NO_CONTENT))
        server.expect(requestTo("http://compute.test/trading-review/jobs/job-1/acknowledgement"))
            .andRespond(withStatus(HttpStatus.CONFLICT))
        val client = RestClientTradingNormalizationClient.withClient("test-internal-key", mapper, builder.build())
        val descriptor = assertIs<NormalizationResultLookup.Found>(client.descriptor("job-1")).descriptor
        assertEquals(hash, descriptor.payloadSha256)
        assertIs<NormalizationPayloadLookup.Found>(client.download(descriptor, deliveryPolicy)).payload.use {
            assertEquals(hash, it.sha256)
            assertEquals(bytes.size.toLong(), it.byteSize)
            assertFalse(java.nio.file.Files.readAllBytes(it.path).contentEquals(bytes))
            assertTrue(it.open().use { stream -> stream.readAllBytes().contentEquals(bytes) })
        }
        assertIs<NormalizationAckOutcome.Accepted>(client.acknowledge(
            NormalizationPayloadAcknowledgement("run-1", "session-1", "job-1", "payload-1",
                hash, "c".repeat(64), "attempt-1", 0, "stable-ack-key", "PERSISTED", now,
                retryPolicyJson = "{}", payloadExpiresAt = now.plusSeconds(3600))))
        assertEquals("RESULT_HASH_MISMATCH", assertIs<NormalizationAckOutcome.Rejected>(client.acknowledge(
            NormalizationPayloadAcknowledgement("run-1", "session-1", "job-1", "payload-1",
                hash, "c".repeat(64), "attempt-1", 0, "stable-ack-key", "PERSISTED", now,
                retryPolicyJson = "{}", payloadExpiresAt = now.plusSeconds(3600)))).code)
        server.verify()
    }

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
                .body("""{"jobId":"job-1","coreLogicalJobId":"$dispatchId","resultSchemaVersion":"TRADING_NORMALIZATION_RESULT_V1","resultHashSpecificationVersion":"TRADING_NORMALIZATION_RESULT_HASH_V1"}"""))
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
            val run = NormalizationRun.restore(NormalizationRunId("00000000-0000-0000-0000-000000000001"), input,
                NormalizationInputCanonicalizer.hash(identity), "e".repeat(64),
                NormalizationRunStatus.DISPATCH_PENDING, 1,
                "00000000-0000-0000-0000-000000000003", null, null, now, now, null, now, null, 0,
                inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
            val dispatch = NormalizationDispatch(dispatchId, run.id.value, "f".repeat(64),
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
            assertEquals(run.resultVersionPin.schemaVersion, first.path("resultSchemaVersion").asText())
            assertEquals(run.resultVersionPin.hashSpecificationVersion,
                first.path("resultHashSpecificationVersion").asText())
            assertEquals(first.path("attemptToken").asText(), second.path("attemptToken").asText())
            assertEquals(first.path("expectedInputSetHash").asText(), second.path("expectedInputSetHash").asText())
            assertEquals(first.path("inputIdentity"), second.path("inputIdentity"))
            assertEquals(first.path("resultDeliveryPolicy"), second.path("resultDeliveryPolicy"))
            assertEquals("RESULT_DELIVERY_V1", first.path("resultDeliveryPolicy")
                .path("policyVersion").asText())
            assertEquals(identity.tradingBookId, first.path("inputIdentity").path("tradingBookId").asText())
            assertEquals(run.id.value, first.path("coreNormalizationRunId").asText())
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
                client.submit(NormalizationRun.restore(run.id, run.input, run.inputFingerprint, run.logicalKey, run.status,
                    run.currentAttempt, run.attemptToken, run.computeJobId, run.lastFailureCode, run.nextAttemptAt,
                    run.createdAt, run.startedAt, run.updatedAt, run.completedAt, run.version, run.retryOfRunId,
                    null, run.resultDeliveryPolicy, run.resultVersionPin), dispatch,
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

    private fun testDescriptor(payloadId: String, byteSize: Long) =
        com.refinvest.core.tradingreview.port.NormalizationResultDescriptor(
            "job-1", "logical-1", "run-1", payloadId, byteSize, "a".repeat(64),
            "attempt-1", 0, "b".repeat(64), "TRADING_INPUT_SET_HASH_V1", emptyMap(),
            "TRADING_NORMALIZATION_RESULT_V1", "TRADING_NORMALIZATION_RESULT_HASH_V1",
            emptyMap(), emptyList(), "c".repeat(64), "d".repeat(64), now.plusSeconds(3600),
            "RESULT_DELIVERY_V1", "identity")

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
