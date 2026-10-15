package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.domain.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.NormalizationRun
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

@Component
class RestClientTradingNormalizationClient private constructor(
    private val apiKey: String,
    private val mapper: ObjectMapper,
    private val client: RestClient,
) : TradingNormalizationComputeClient {
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
                            if (jobId.isNullOrBlank() || accepted.path("coreLogicalJobId").asText() != dispatch.id)
                                NormalizationSubmission.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID")
                            else NormalizationSubmission.Accepted(jobId)
                        }
                        503, 429 -> NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE",
                            response.headers.getFirst("Retry-After")?.toLongOrNull()?.let(Duration::ofSeconds))
                        409 -> NormalizationSubmission.Rejected(
                            response.bodyTo(String::class.java)?.let { safeCode(it) }
                                ?: "IDEMPOTENCY_PAYLOAD_CONFLICT")
                        401, 403 -> NormalizationSubmission.Rejected("INTERNAL_AUTHENTICATION_FAILED")
                        400, 422 -> NormalizationSubmission.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID")
                        in 500..599 -> NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE")
                        else -> NormalizationSubmission.Rejected("INTERNAL_REQUEST_CONTRACT_INVALID")
                    }
                }
        } catch (_: Exception) {
            // Never log the request or an exception: transport errors can contain a signed URI.
            NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE")
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
                            NormalizationStatusLookup.Found(NormalizationJobStatus(jobId,
                                body.path("status").asText(), body.path("attemptToken").asText(),
                                body.path("deletionGeneration").asLong(), body.path("grantRevision").asLong(),
                                body.path("expectedInputSetHash").asText(),
                                body.path("targetVersionSet").path("versionSetHash").asText(),
                                body.path("grantAction").asText("NONE"),
                                body.path("grantRefreshDeadlineAt").takeIf { !it.isNull && !it.isMissingNode }
                                    ?.asText()?.let(java.time.Instant::parse),
                                body.path("failure").path("code").takeIf { !it.isMissingNode }?.asText()))
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

    private fun safeCode(body: String): String? = try {
        mapper.readTree(body).path("code").asText().takeIf {
            it in setOf("IDEMPOTENCY_PAYLOAD_CONFLICT", "ARTIFACT_IDENTITY_CONFLICT")
        }
    } catch (_: Exception) { null }

    private fun body(run: NormalizationRun, dispatch: NormalizationDispatch,
                     grants: List<ArtifactReadGrant>): Map<String, Any> {
        val input = run.input
        val identity = requireNotNull(run.inputIdentity) { "Missing normalization identity snapshot" }
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
