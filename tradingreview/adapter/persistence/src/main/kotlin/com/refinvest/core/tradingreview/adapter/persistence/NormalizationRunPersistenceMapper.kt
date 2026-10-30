package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import tools.jackson.databind.json.JsonMapper

/** Pure conversion of supplied persistence records, including legacy absent execution pins. */
internal object NormalizationRunPersistenceMapper {
    private val mapper = JsonMapper.builder().build()
    fun toRecord(run: NormalizationRun): NormalizationRunRecord {
        val input = run.input
        val identityJson = run.inputIdentity?.let { identity ->
            verifyIdentity(input, identity, run.inputFingerprint)
            String(NormalizationInputCanonicalizer.canonicalize(identity), Charsets.UTF_8)
        }
        val descriptors = input.artifacts.map { mapOf("artifactId" to it.artifactId.value,
            "role" to it.role.name, "sha256" to it.sha256, "contentLength" to it.contentLength,
            "mediaType" to it.mediaType, "dialect" to it.dialect) }
        val inputJson = mapper.writeValueAsString(mapOf("artifacts" to descriptors, "venue" to input.venue,
            "productFamily" to input.productFamily, "positionMode" to input.positionMode,
            "settlementAsset" to input.settlementAsset, "sourceTimezone" to input.sourceTimezone,
            "reviewTimezone" to input.reviewTimezone))
        return NormalizationRunRecord(
            id = run.id.value,
            retryOfRunId = run.retryOfRunId?.value,
            importSessionId = input.importSessionId.value,
            tradingBookId = input.tradingBookId.value,
            ownerMemberId = input.ownerId,
            inputFingerprint = run.inputFingerprint,
            logicalKey = run.logicalKey,
            inputJson = inputJson,
            inputIdentityJson = identityJson,
            targetVersionSetJson = input.versionSetJson,
            instrumentTermsJson = input.instrumentTermsJson,
            dataPolicyVersion = input.dataPolicyVersion,
            retryPolicyVersion = input.retryPolicyVersion,
            retryPolicyJson = input.retryPolicyJson,
            resultDeliveryPolicyJson = run.resultDeliveryPolicy?.let { mapper.writeValueAsString(it.toWireValue()) },
            deletionGeneration = input.deletionGeneration,
            status = run.status.name,
            currentAttempt = run.currentAttempt,
            attemptToken = run.attemptToken,
            computeJobId = run.computeJobId,
            lastFailureCode = run.lastFailureCode,
            nextAttemptAt = run.nextAttemptAt,
            createdAt = run.createdAt,
            startedAt = run.startedAt,
            updatedAt = run.updatedAt,
            completedAt = run.completedAt,
            version = run.version,
            resultSchemaVersion = run.resultVersionPin.schemaVersion,
            resultHashSpecificationVersion = run.resultVersionPin.hashSpecificationVersion,
        )
    }

    fun toDomain(record: NormalizationRunRecord): NormalizationRun {
        val data = mapper.readTree(record.inputJson)
        val artifactArray = data.path("artifacts")
        val inputs = (0 until artifactArray.size()).map { index ->
            val a = artifactArray.path(index)
            NormalizationArtifactInput(
            TradingSourceArtifactId(a.path("artifactId").asLong()),
            TradingArtifactRole.valueOf(a.path("role").asText()), a.path("sha256").asText(),
            a.path("contentLength").asLong(), a.path("mediaType").asText(), a.path("dialect").asText()) }
        val input = NormalizationInput(TradingBookId(record.tradingBookId),
            TradingImportSessionId(record.importSessionId), record.ownerMemberId,
            inputs, data.path("venue").asText(), data.path("productFamily").asText(),
            data.path("positionMode").asText(), data.path("settlementAsset").asText(),
            data.path("sourceTimezone").asText(), data.path("reviewTimezone").asText(),
            record.targetVersionSetJson, record.instrumentTermsJson,
            record.dataPolicyVersion,
            record.retryPolicyVersion, record.retryPolicyJson,
            record.deletionGeneration)
        val identity = record.inputIdentityJson?.let {
            NormalizationInputIdentity.fromWireValue(mapper.readValue(it, Map::class.java))
        }
        if (identity != null) verifyIdentity(input, identity, record.inputFingerprint)
        return NormalizationRun.restore(NormalizationRunId(record.id), input, record.inputFingerprint,
            record.logicalKey, NormalizationRunStatus.valueOf(record.status),
            record.currentAttempt, record.attemptToken,
            record.computeJobId, record.lastFailureCode,
            record.nextAttemptAt, record.createdAt,
            record.startedAt, record.updatedAt,
            record.completedAt, record.version,
            record.retryOfRunId?.let(::NormalizationRunId), identity,
            record.resultDeliveryPolicyJson?.let {
                NormalizationResultDeliveryPolicy.fromWireValue(mapper.readValue(it, Map::class.java))
            }, NormalizationResultVersionPin(record.resultSchemaVersion,
                record.resultHashSpecificationVersion))
    }

    private fun verifyIdentity(input: NormalizationInput, identity: NormalizationInputIdentity,
                               inputFingerprint: String) {
        require(NormalizationInputCanonicalizer.hash(identity) == inputFingerprint) {
            "Persisted normalization identity/hash mismatch"
        }
        val pinnedVersions = mapper.readValue(input.versionSetJson, Map::class.java)
        val expected = NormalizationInputIdentity.fromPinnedInput(input, pinnedVersions)
        require(NormalizationInputCanonicalizer.canonicalize(identity).contentEquals(
            NormalizationInputCanonicalizer.canonicalize(expected))) {
            "Persisted normalization identity/input mismatch"
        }
    }

}
