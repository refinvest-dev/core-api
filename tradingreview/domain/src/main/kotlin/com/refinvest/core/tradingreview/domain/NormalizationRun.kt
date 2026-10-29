package com.refinvest.core.tradingreview.domain

import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId

import java.time.Instant

enum class NormalizationRunStatus {
    DISPATCH_PENDING, SUBMITTED, RUNNING, RESULT_READY, PUBLISHING, PUBLISHED,
    RETRY_WAIT, TERMINAL_FAILURE, OBSOLETE
}
enum class NormalizationDispatchStatus { PENDING, SUBMITTED, RESULT_READY, PUBLISHED, TERMINAL_FAILURE, OBSOLETE }

data class NormalizationArtifactInput(
    val artifactId: TradingSourceArtifactId,
    val role: TradingArtifactRole,
    val sha256: String,
    val contentLength: Long,
    val mediaType: String,
    val dialect: String,
)

data class NormalizationInput(
    val tradingBookId: TradingBookId,
    val importSessionId: TradingImportSessionId,
    val ownerId: Long,
    val artifacts: List<NormalizationArtifactInput>,
    val venue: String,
    val productFamily: String,
    val positionMode: String,
    val settlementAsset: String,
    val sourceTimezone: String,
    val reviewTimezone: String,
    val versionSetJson: String,
    val instrumentTermsJson: String,
    val dataPolicyVersion: String,
    val retryPolicyVersion: String,
    val retryPolicyJson: String,
    val deletionGeneration: Long,
)

data class NormalizationRun(
    val id: String,
    val input: NormalizationInput,
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
    val retryOfRunId: String? = null,
    // Null only for pre-ADR-065 runs. Such a run must never be submitted or replayed as V1.
    val inputIdentity: NormalizationInputIdentity? = null,
    // Null only for pre-ADR-067 runs. Never infer a new transport policy for an old job.
    val resultDeliveryPolicy: NormalizationResultDeliveryPolicy? = null,
    val resultVersionPin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
)

data class NormalizationDispatch(
    val id: String,
    val runId: String,
    val computeKey: String,
    val status: NormalizationDispatchStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val lastFailureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pollFailureCount: Int = 0,
    val resultVersionPin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
)
