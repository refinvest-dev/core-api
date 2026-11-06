package com.refinvest.core.tradingreview.domain.importsession

import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.common.domain.DomainEntity
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.util.Collections
import java.util.UUID

class NormalizationRun private constructor(
    id: NormalizationRunId,
    input: NormalizationInput,
    val inputFingerprint: String,
    val logicalKey: String,
    val status: NormalizationRunStatus,
    val currentAttempt: Int,
    val attemptToken: String,
    val computeJobId: String?,
    val lastFailureCode: String?,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val version: Long,
    val retryOfRunId: NormalizationRunId? = null,
    val inputIdentity: NormalizationInputIdentity? = null,
    resultDeliveryPolicy: NormalizationResultDeliveryPolicy? = null,
    val resultVersionPin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
) : DomainEntity<NormalizationRunId>(id) {
    val input = input.copy(artifacts = Collections.unmodifiableList(input.artifacts.toList()))
    val resultDeliveryPolicy = resultDeliveryPolicy?.copy(
        acceptedContentEncodings = Collections.unmodifiableList(resultDeliveryPolicy.acceptedContentEncodings.toList()))

    init {
        val restoredInput = this.input
        require(currentAttempt >= 1 && version >= 0) { "Invalid attempt or version" }
        require(Regex("[0-9a-f]{64}").matches(inputFingerprint) && logicalKey.isNotBlank())
        require(UUID.fromString(attemptToken).toString() == attemptToken) { "Invalid attempt token" }
        require(!updatedAt.isBefore(createdAt)) { "Run update precedes creation" }
        require(startedAt == null || !startedAt.isBefore(createdAt))
        require(completedAt == null || !completedAt.isBefore(createdAt))
        val terminal = status in setOf(NormalizationRunStatus.PUBLISHED, NormalizationRunStatus.TERMINAL_FAILURE, NormalizationRunStatus.OBSOLETE)
        require(inputIdentity == null || terminal == (completedAt != null)) { "Run completion and status disagree" }
        require(retryOfRunId != id) { "Run cannot retry itself" }
        require(inputIdentity == null || (restoredInput.artifacts.size == 2 && listOf(restoredInput.venue, restoredInput.productFamily,
            restoredInput.positionMode, restoredInput.settlementAsset, restoredInput.sourceTimezone, restoredInput.reviewTimezone, restoredInput.versionSetJson,
            restoredInput.instrumentTermsJson, restoredInput.dataPolicyVersion, restoredInput.retryPolicyVersion, restoredInput.retryPolicyJson).all { it.isNotBlank() }))
        if (inputIdentity != null) {
            require(inputIdentity.tradingBookId == restoredInput.tradingBookId.value.toString() &&
                inputIdentity.sourceImportSessionIds == listOf(restoredInput.importSessionId.value.toString()) &&
                inputIdentity.sourceTimezone == restoredInput.sourceTimezone && inputIdentity.reviewTimezone == restoredInput.reviewTimezone &&
                inputIdentity.declaredPositionMode == restoredInput.positionMode &&
                inputIdentity.declaredProduct.venue == restoredInput.venue && inputIdentity.declaredProduct.productFamily == restoredInput.productFamily &&
                inputIdentity.declaredProduct.settlementAsset == restoredInput.settlementAsset &&
                restoredInput.artifacts.all { artifact -> inputIdentity.artifactsByRole[artifact.role.name]?.let {
                    it.artifactId == artifact.artifactId.value.toString() && it.sha256 == artifact.sha256
                } == true }) { "Normalization identity/descriptor mismatch" }
        }
        require(inputIdentity == null || NormalizationInputCanonicalizer.hash(inputIdentity) == inputFingerprint) {
            "Normalization identity/hash mismatch"
        }
    }

    companion object {
        /** Restoration preserves legacy absent pins; execution must fail closed for those rows. */
        fun restore(
            id: NormalizationRunId,
            input: NormalizationInput,
            inputFingerprint: String,
            logicalKey: String,
            status: NormalizationRunStatus,
            currentAttempt: Int,
            attemptToken: String,
            computeJobId: String?,
            lastFailureCode: String?,
            nextAttemptAt: Instant,
            createdAt: Instant,
            startedAt: Instant?,
            updatedAt: Instant,
            completedAt: Instant?,
            version: Long,
            retryOfRunId: NormalizationRunId? = null,
            inputIdentity: NormalizationInputIdentity? = null,
            resultDeliveryPolicy: NormalizationResultDeliveryPolicy? = null,
            resultVersionPin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
        ) = NormalizationRun(
            id, input, inputFingerprint, logicalKey, status,
            currentAttempt, attemptToken, computeJobId, lastFailureCode, nextAttemptAt,
            createdAt, startedAt, updatedAt, completedAt, version,
            retryOfRunId, inputIdentity, resultDeliveryPolicy, resultVersionPin,
        )

        internal fun create(id: NormalizationRunId, input: NormalizationInput, fingerprint: String,
                            logicalKey: String, attemptToken: String, identity: NormalizationInputIdentity,
                            deliveryPolicy: NormalizationResultDeliveryPolicy,
                            resultPin: NormalizationResultVersionPin, retryOf: NormalizationRunId?, now: Instant) =
            NormalizationRun(id, input, fingerprint, logicalKey, NormalizationRunStatus.DISPATCH_PENDING,
                1, attemptToken, null, null, now, now, null, now, null, 0, retryOf, identity, deliveryPolicy, resultPin)
    }
}
