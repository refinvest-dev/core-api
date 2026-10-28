package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.domain.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.NormalizationRun
import com.refinvest.core.tradingreview.domain.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.port.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class RestClientTradingNormalizationClient private constructor(
    private val apiKey: String,
    private val mapper: ObjectMapper,
    private val client: RestClient,
) : TradingNormalizationComputeClient, TradingNormalizationResultClient {
    @Autowired
    constructor(@Value("\${refinvest.compute.base-url:http://localhost:8000}") baseUrl: String,
                @Value("\${REFINVEST_INTERNAL_API_KEY:}") apiKey: String,
                mapper: ObjectMapper) : this(apiKey, mapper, RestClient.builder().baseUrl(baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build()).apply {
            setReadTimeout(Duration.ofSeconds(20))
        }).build())

    companion object {
        internal fun withClient(apiKey: String, mapper: ObjectMapper, client: RestClient) =
            RestClientTradingNormalizationClient(apiKey, mapper, client)
    }

    override fun submit(run: NormalizationRun, dispatch: NormalizationDispatch,
                        grants: List<ArtifactReadGrant>): NormalizationSubmission {
        if (run.resultVersionPin != dispatch.resultVersionPin)
            return NormalizationSubmission.Rejected("RESULT_VERSION_PIN_MISMATCH")
        if (apiKey.isBlank()) return NormalizationSubmission.Rejected("INTERNAL_AUTHENTICATION_FAILED")
        val body = try { body(run, dispatch, grants) }
            catch (_: Exception) { return NormalizationSubmission.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID") }
        return try {
            client.post().uri("/trading-review/normalization-jobs")
                .header("X-Internal-Api-Key", apiKey)
                .header("Idempotency-Key", dispatch.computeKey)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange { _, response ->
                    when (response.statusCode.value()) {
                        202 -> {
                            val accepted = response.bodyTo(String::class.java)?.let(mapper::readTree)
                            val jobId = accepted?.path("jobId")?.asText()
                            val pinError = pinFailure(accepted?.path("resultSchemaVersion")?.asText() ?: "",
                                accepted?.path("resultHashSpecificationVersion")?.asText() ?: "", run)
                            if (jobId.isNullOrBlank() || accepted.path("coreLogicalJobId").asText() != dispatch.id)
                                NormalizationSubmission.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID")
                            else if (pinError != null) NormalizationSubmission.Rejected(pinError)
                            else NormalizationSubmission.Accepted(jobId)
                        }
                        503, 429 -> NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE",
                            response.headers.getFirst("Retry-After")?.toLongOrNull()?.let(Duration::ofSeconds))
                        409 -> NormalizationSubmission.Rejected(
                            response.bodyTo(String::class.java)?.let { safeCode(it) }
                                ?: "IDEMPOTENCY_PAYLOAD_CONFLICT")
                        401, 403 -> NormalizationSubmission.Rejected("INTERNAL_AUTHENTICATION_FAILED")
                        400, 422 -> NormalizationSubmission.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID")
                        in 500..599 -> NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE", outcomeUnknown = true)
                        else -> NormalizationSubmission.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID")
                    }
                }
        } catch (_: Exception) {
            // The server may have accepted the exact identity before the response was lost.
            NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE", outcomeUnknown = true)
        }
    }

    override fun status(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationStatusLookup {
        val jobId = run.computeJobId ?: return NormalizationStatusLookup.Rejected("INTERNAL_ERROR")
        if (apiKey.isBlank()) return NormalizationStatusLookup.Rejected("INTERNAL_ERROR")
        return try {
            client.get().uri("/trading-review/normalization-jobs/{jobId}", jobId)
                .header("X-Internal-Api-Key", apiKey).exchange { _, response ->
                    when (response.statusCode.value()) {
                        200 -> {
                            val body = response.bodyTo(String::class.java)?.let(mapper::readTree)
                                ?: return@exchange NormalizationStatusLookup.Rejected("INTERNAL_ERROR")
                            val identity = run.inputIdentity
                                ?: return@exchange NormalizationStatusLookup.Rejected("INTERNAL_ERROR")
                            val versions = mapper.valueToTree<tools.jackson.databind.JsonNode>(
                                identity.targetVersionSet.toWireValue())
                            if (body.path("jobId").asText() != jobId ||
                                body.path("coreLogicalJobId").asText() != dispatch.id ||
                                body.path("coreNormalizationRunId").asText() != run.id ||
                                body.path("hashSpecificationVersion").asText() != identity.hashSpecificationVersion ||
                                body.path("targetVersionSet") != versions)
                                return@exchange NormalizationStatusLookup.Rejected("INTERNAL_ERROR")
                            val pinError = pinFailure(body.path("resultSchemaVersion").asText(),
                                body.path("resultHashSpecificationVersion").asText(), run)
                            if (pinError != null) return@exchange NormalizationStatusLookup.Rejected(pinError)
                            NormalizationStatusLookup.Found(NormalizationJobStatus(jobId,
                                body.path("status").asText(), body.path("attemptToken").asText(),
                                body.path("deletionGeneration").asLong(), body.path("grantRevision").asLong(),
                                body.path("expectedInputSetHash").asText(),
                                body.path("targetVersionSet").path("versionSetHash").asText(),
                                body.path("grantAction").asText("NONE"),
                                body.path("grantRefreshDeadlineAt").takeIf { !it.isNull && !it.isMissingNode }
                                    ?.asText()?.let(java.time.Instant::parse),
                                body.path("failure").path("code").takeIf { !it.isMissingNode }?.asText(),
                                run.resultVersionPin))
                        }
                        401, 403 -> NormalizationStatusLookup.Rejected("INTERNAL_ERROR")
                        400, 422 -> NormalizationStatusLookup.Rejected("INTERNAL_ERROR")
                        else -> NormalizationStatusLookup.Retry("TEMPORARY_DEPENDENCY_FAILURE")
                    }
                }
        } catch (_: Exception) { NormalizationStatusLookup.Retry("TEMPORARY_DEPENDENCY_FAILURE") }
    }

    override fun updateGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                              current: NormalizationJobStatus, nextAttemptToken: String,
                              grants: List<ArtifactReadGrant>, updateKey: String): NormalizationGrantUpdate {
        if (apiKey.isBlank()) return NormalizationGrantUpdate.Rejected("INTERNAL_ERROR")
        val grantValues = try { grantBody(run, grants) }
            catch (_: Exception) { return NormalizationGrantUpdate.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID") }
        val body = mapOf(
            "mode" to current.grantAction,
            "expectedGrantRevision" to current.grantRevision,
            "expectedAttemptToken" to current.attemptToken,
            "nextAttemptToken" to nextAttemptToken,
            "expectedInputSetHash" to run.inputFingerprint,
            "versionSetHash" to current.versionSetHash,
            "deletionGeneration" to run.input.deletionGeneration,
            "artifactAccessGrants" to grantValues)
        return try {
            client.post().uri("/trading-review/normalization-jobs/{jobId}/grant-updates", current.jobId)
                .header("X-Internal-Api-Key", apiKey).header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON).body(body).exchange { _, response ->
                    when (response.statusCode.value()) {
                        200 -> NormalizationGrantUpdate.Accepted
                        409 -> NormalizationGrantUpdate.Conflict
                        401, 403, 400, 422 -> NormalizationGrantUpdate.Rejected("INTERNAL_ERROR")
                        else -> NormalizationGrantUpdate.Retry
                    }
                }
        } catch (_: Exception) { NormalizationGrantUpdate.Retry }
    }

    override fun descriptor(jobId: String): NormalizationResultLookup {
        if (apiKey.isBlank()) return NormalizationResultLookup.Rejected("INTERNAL_AUTHENTICATION_FAILED")
        return try {
            client.get().uri("/trading-review/normalization-jobs/{jobId}/result", jobId)
                .header("X-Internal-Api-Key", apiKey).exchange { _, response ->
                    when (response.statusCode.value()) {
                        200 -> try {
                            val body = mapper.readTree(response.body)
                            if (body.path("transportMode").asText() != "SINGLE_RESOURCE" ||
                                body.path("mediaType").asText() !=
                                "application/vnd.refinvest.trading-normalization+json")
                                throw IllegalArgumentException("Unsupported transport")
                            @Suppress("UNCHECKED_CAST")
                            fun objectValue(name: String): Map<String, Any?> = mapper.readValue(
                                mapper.writeValueAsString(body.path(name)), Map::class.java) as Map<String, Any?>
                            @Suppress("UNCHECKED_CAST")
                            val digests = mapper.readValue(mapper.writeValueAsString(body.path("sectionDigests")),
                                List::class.java) as List<Any?>
                            NormalizationResultLookup.Found(NormalizationResultDescriptor(
                                body.path("jobId").asText(), body.path("coreLogicalJobId").asText(),
                                body.path("coreNormalizationRunId").asText(), body.path("payloadId").asText(),
                                body.path("byteSize").asLong(), body.path("payloadSha256").asText(),
                                body.path("attemptToken").asText(), body.path("deletionGeneration").asLong(),
                                body.path("inputSetHash").asText(), body.path("hashSpecificationVersion").asText(),
                                objectValue("targetVersionSet"), body.path("resultSchemaVersion").asText(),
                                body.path("resultHashSpecificationVersion").asText(), objectValue("counts"),
                                digests, body.path("reconciliationManifestHash").asText(),
                                body.path("resultHash").asText(), Instant.parse(body.path("expiresAt").asText()),
                                body.path("resultDeliveryPolicyVersion").asText().takeIf { it == "RESULT_DELIVERY_V1" }
                                    ?: throw IllegalArgumentException("Unsupported delivery policy"),
                                body.path("contentEncoding").asText().takeIf { it.isNotBlank() }
                                    ?: throw IllegalArgumentException("Missing content encoding")))
                        } catch (_: Exception) { NormalizationResultLookup.Rejected("RESULT_SCHEMA_UNSUPPORTED") }
                        401, 403 -> NormalizationResultLookup.Rejected("INTERNAL_AUTHENTICATION_FAILED")
                        404, 409 -> NormalizationResultLookup.Rejected("RESULT_NOT_AVAILABLE")
                        else -> NormalizationResultLookup.Retry("TEMPORARY_DEPENDENCY_FAILURE")
                    }
                }
        } catch (_: Exception) { NormalizationResultLookup.Retry("TEMPORARY_DEPENDENCY_FAILURE") }
    }

    override fun download(descriptor: NormalizationResultDescriptor,
                          policy: NormalizationResultDeliveryPolicy): NormalizationPayloadLookup {
        if (apiKey.isBlank()) return NormalizationPayloadLookup.Rejected("INTERNAL_AUTHENTICATION_FAILED")
        if (descriptor.byteSize > policy.maxAcceptedPayloadBytes)
            return NormalizationPayloadLookup.Rejected("RESULT_PAYLOAD_LIMIT_EXCEEDED")
        return try {
            client.get().uri("/trading-review/terminal-payloads/{payloadId}", descriptor.payloadId)
                .header("X-Internal-Api-Key", apiKey)
                .header("Accept-Encoding", "identity")
                .exchange { _, response ->
                    when (response.statusCode.value()) {
                        200 -> {
                            if (response.headers.getFirst("Content-Encoding")?.lowercase() !in setOf(null, "identity"))
                                return@exchange NormalizationPayloadLookup.Rejected("RESULT_CONTENT_ENCODING_UNSUPPORTED")
                            if (response.headers.contentType?.let {
                                    it.type != "application" || it.subtype != "vnd.refinvest.trading-normalization+json" ||
                                        (it.charset != null && it.charset != Charsets.UTF_8)
                                } != false) return@exchange NormalizationPayloadLookup.Rejected("RESULT_SCHEMA_UNSUPPORTED")
                            val headerLength = response.headers.contentLength
                            if (headerLength > policy.maxAcceptedPayloadBytes)
                                return@exchange NormalizationPayloadLookup.Rejected("RESULT_PAYLOAD_LIMIT_EXCEEDED")
                            if (headerLength >= 0 && headerLength != descriptor.byteSize)
                                return@exchange NormalizationPayloadLookup.Rejected("RESULT_PAYLOAD_LENGTH_MISMATCH")
                            val path = NormalizationResultSpool.create()
                            try {
                                val random = SecureRandom()
                                val spoolKey = ByteArray(32).also(random::nextBytes)
                                val spoolIv = ByteArray(12).also(random::nextBytes)
                                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(spoolKey, "AES"),
                                    GCMParameterSpec(128, spoolIv))
                                val digest = MessageDigest.getInstance("SHA-256")
                                val size = DigestInputStream(response.body, digest).use { input ->
                                    CipherOutputStream(Files.newOutputStream(path), cipher).use { output ->
                                        val buffer = ByteArray(8192)
                                        var total = 0L
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            total += count
                                            if (total > policy.maxAcceptedPayloadBytes) throw PayloadTooLarge()
                                            if (total > descriptor.byteSize) throw PayloadLengthMismatch()
                                            output.write(buffer, 0, count)
                                        }
                                        if (total != descriptor.byteSize ||
                                            (headerLength >= 0 && total != headerLength))
                                            throw PayloadLengthMismatch()
                                        total
                                    }
                                }
                                NormalizationPayloadLookup.Found(NormalizationPayloadFile(path, size,
                                    HexFormat.of().formatHex(digest.digest()), spoolKey, spoolIv))
                            } catch (_: PayloadTooLarge) {
                                Files.deleteIfExists(path)
                                NormalizationPayloadLookup.Rejected("RESULT_PAYLOAD_LIMIT_EXCEEDED")
                            } catch (_: PayloadLengthMismatch) {
                                Files.deleteIfExists(path)
                                NormalizationPayloadLookup.Rejected("RESULT_PAYLOAD_LENGTH_MISMATCH")
                            } catch (_: Exception) {
                                Files.deleteIfExists(path)
                                NormalizationPayloadLookup.Retry("RESULT_PAYLOAD_STREAM_ABORTED")
                            }
                        }
                        401, 403 -> NormalizationPayloadLookup.Rejected("INTERNAL_AUTHENTICATION_FAILED")
                        404 -> NormalizationPayloadLookup.Rejected("RESULT_NOT_AVAILABLE")
                        else -> NormalizationPayloadLookup.Retry("TEMPORARY_DEPENDENCY_FAILURE")
                    }
                }
        } catch (_: Exception) { NormalizationPayloadLookup.Retry("TEMPORARY_DEPENDENCY_FAILURE") }
    }

    private class PayloadTooLarge : RuntimeException()
    private class PayloadLengthMismatch : RuntimeException()

    override fun acknowledge(acknowledgement: NormalizationPayloadAcknowledgement): NormalizationAckOutcome {
        if (apiKey.isBlank()) return NormalizationAckOutcome.Rejected("INTERNAL_ERROR")
        val body = mutableMapOf<String, Any>(
            "coreAggregateId" to acknowledgement.coreImportSessionId,
            "payloadId" to acknowledgement.payloadId,
            "payloadSha256" to acknowledgement.payloadSha256,
            "terminalResultHash" to acknowledgement.resultHash,
            "attemptToken" to acknowledgement.attemptToken,
            "deletionGeneration" to acknowledgement.deletionGeneration,
            "disposition" to acknowledgement.disposition)
        if (acknowledgement.disposition == "PERSISTED")
            body["persistedAt"] = acknowledgement.occurredAt.toString()
        else {
            body["discardedAt"] = acknowledgement.occurredAt.toString()
            body["currentDeletionGeneration"] = requireNotNull(acknowledgement.currentDeletionGeneration)
        }
        return try {
            client.post().uri("/trading-review/jobs/{jobId}/acknowledgement", acknowledgement.jobId)
                .header("X-Internal-Api-Key", apiKey)
                .header("Idempotency-Key", acknowledgement.idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange { _, response -> when (response.statusCode.value()) {
                    204 -> NormalizationAckOutcome.Accepted
                    409 -> NormalizationAckOutcome.Rejected("RESULT_HASH_MISMATCH")
                    404 -> NormalizationAckOutcome.Rejected("RESULT_NOT_AVAILABLE")
                    400, 401, 403, 422 -> NormalizationAckOutcome.Rejected("INTERNAL_ERROR")
                    else -> NormalizationAckOutcome.Retry
                } }
        } catch (_: Exception) { NormalizationAckOutcome.Retry }
    }

    private fun safeCode(body: String): String? = try {
        mapper.readTree(body).path("code").asText().takeIf {
            it in setOf("IDEMPOTENCY_PAYLOAD_CONFLICT", "ARTIFACT_IDENTITY_CONFLICT")
        }
    } catch (_: Exception) { null }

    private fun pinFailure(schema: String, hash: String, run: NormalizationRun): String? = when {
        !NormalizationResultVersionPin.isSupported(schema, hash) -> "RESULT_SCHEMA_UNSUPPORTED"
        schema != run.resultVersionPin.schemaVersion ||
            hash != run.resultVersionPin.hashSpecificationVersion -> "RESULT_VERSION_PIN_MISMATCH"
        else -> null
    }

    private fun body(run: NormalizationRun, dispatch: NormalizationDispatch,
                     grants: List<ArtifactReadGrant>): Map<String, Any> {
        val input = run.input
        val identity = requireNotNull(run.inputIdentity) { "Missing normalization identity snapshot" }
        val deliveryPolicy = requireNotNull(run.resultDeliveryPolicy) { "Missing result delivery policy snapshot" }
        require(NormalizationInputCanonicalizer.hash(identity) == run.inputFingerprint) {
            "Normalization identity/hash mismatch"
        }
        require(identity.tradingBookId == input.tradingBookId.value.toString() &&
            identity.sourceImportSessionIds == listOf(input.importSessionId.value.toString())) {
            "Normalization identity/run mismatch"
        }
        val grantValues = grantBody(run, grants)
        val terms = mapper.readValue(input.instrumentTermsJson, List::class.java)
            .sortedBy { (it as Map<*, *>)["instrumentId"].toString() }
        val termVersions = identity.targetVersionSet.reconstruction.instrumentTermsVersionByInstrument
        require(terms.all { term ->
            val data = term as Map<*, *>
            val id = data["instrumentId"] as? String ?: return@all false
            termVersions[id] == data["instrumentTermsVersion"]
        })
        return mapOf(
            "coreLogicalJobId" to dispatch.id,
            "coreNormalizationRunId" to run.id,
            "coreImportSessionId" to input.importSessionId.value.toString(),
            "inputIdentity" to identity.toWireValue(),
            "artifactAccessGrants" to grantValues,
            "pinnedInstrumentTerms" to terms,
            "expectedInputSetHash" to run.inputFingerprint,
            "resultDeliveryPolicy" to deliveryPolicy.toWireValue(),
            "resultSchemaVersion" to run.resultVersionPin.schemaVersion,
            "resultHashSpecificationVersion" to run.resultVersionPin.hashSpecificationVersion,
            "retryPolicyVersion" to input.retryPolicyVersion,
            "deletionGeneration" to input.deletionGeneration,
            "attemptToken" to run.attemptToken)
    }

    private fun grantBody(run: NormalizationRun, grants: List<ArtifactReadGrant>): List<Map<String, Any>> {
        val identity = requireNotNull(run.inputIdentity) { "Missing normalization identity snapshot" }
        require(NormalizationInputCanonicalizer.hash(identity) == run.inputFingerprint) {
            "Normalization identity/hash mismatch"
        }
        val input = run.input
        val pinned = input.artifacts.associateBy { it.role.name }
        require(pinned.keys == identity.artifactsByRole.keys && grants.size == 2 &&
            grants.map { it.role }.toSet() == identity.artifactsByRole.keys) {
            "Artifact grant role mismatch"
        }
        require(grants.all { grant ->
            val artifact = pinned[grant.role]
            val declared = identity.artifactsByRole[grant.role]
            artifact != null && declared != null &&
                grant.coreArtifactId == declared.artifactId && grant.rawSha256 == declared.sha256 &&
                artifact.artifactId.value.toString() == declared.artifactId &&
                artifact.sha256 == declared.sha256 && artifact.contentLength == grant.byteSize &&
                artifact.mediaType == grant.contentType
        }) { "Artifact grant identity mismatch" }
        return grants.sortedBy { if (it.role == "TRADE_HISTORY") 0 else 1 }.map { mapOf(
                "coreArtifactId" to it.coreArtifactId, "role" to it.role,
                "rawSha256" to it.rawSha256, "byteSize" to it.byteSize,
                "contentType" to it.contentType, "accessUri" to it.accessUri,
                "expiresAt" to it.expiresAt.toString()) }
    }
}
