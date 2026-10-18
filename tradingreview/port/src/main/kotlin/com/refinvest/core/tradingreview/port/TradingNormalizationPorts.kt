package com.refinvest.core.tradingreview.port

import com.refinvest.core.tradingreview.domain.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

interface TradingNormalizationStore {
    fun insert(run: NormalizationRun, dispatch: NormalizationDispatch)
    fun findRun(id: String): NormalizationRun?
    fun findActive(sessionId: TradingImportSessionId): NormalizationRun?
    fun claimNext(now: Instant, lease: Duration, workerId: String): NormalizationDispatch?
    fun claimSubmitted(now: Instant, lease: Duration, workerId: String): NormalizationDispatch?
    fun schedulePoll(dispatchId: String, leaseOwner: String, next: Instant, now: Instant): Boolean
    fun schedulePollFailure(dispatchId: String, leaseOwner: String, code: String,
                            next: Instant, now: Instant): Boolean
    fun markResultReady(dispatchId: String, leaseOwner: String, now: Instant): Boolean
    fun advanceAttempt(runId: String, dispatchId: String, leaseOwner: String,
                       expectedToken: String, nextToken: String, now: Instant): Boolean
    fun restartAfterJobFailure(runId: String, dispatchId: String, leaseOwner: String,
                               nextDispatch: NormalizationDispatch, nextToken: String,
                               failureCode: String, now: Instant): Boolean
    fun markSubmitted(dispatchId: String, leaseOwner: String, runId: String, generation: Long,
                      attemptToken: String, jobId: String, now: Instant): Boolean
    fun scheduleRetry(dispatchId: String, leaseOwner: String, code: String, next: Instant, now: Instant): Boolean
    fun markTerminal(dispatchId: String, leaseOwner: String, code: String, now: Instant): Boolean
    fun markObsolete(dispatchId: String, leaseOwner: String, now: Instant): Boolean
    fun audit(runId: String, event: String, code: String?, now: Instant)
}

data class ArtifactReadGrant(
    val coreArtifactId: String,
    val role: String,
    val rawSha256: String,
    val byteSize: Long,
    val contentType: String,
    val accessUri: String,
    val expiresAt: Instant,
)

data class ArtifactRedemption(val input: InputStream, val contentLength: Long, val mediaType: String)

interface ArtifactReadGrantRedeemer { fun redeem(token: String): ArtifactRedemption }

interface ArtifactAccessGrantIssuer {
    fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                       sessionId: TradingImportSessionId, attemptToken: String,
                       deletionGeneration: Long): ArtifactReadGrant
}

sealed interface NormalizationSubmission {
    data class Accepted(val jobId: String) : NormalizationSubmission
    data class Retry(val code: String, val retryAfter: Duration? = null) : NormalizationSubmission
    data class Rejected(val code: String) : NormalizationSubmission
}

data class NormalizationJobStatus(
    val jobId: String, val state: String, val attemptToken: String,
    val deletionGeneration: Long, val grantRevision: Long,
    val expectedInputSetHash: String, val versionSetHash: String,
    val grantAction: String, val grantRefreshDeadlineAt: Instant?,
    val failureCode: String?,
)
sealed interface NormalizationStatusLookup {
    data class Found(val status: NormalizationJobStatus) : NormalizationStatusLookup
    data class Retry(val code: String) : NormalizationStatusLookup
    data class Rejected(val code: String) : NormalizationStatusLookup
}
sealed interface NormalizationGrantUpdate {
    data object Accepted : NormalizationGrantUpdate
    data object Retry : NormalizationGrantUpdate
    data object Conflict : NormalizationGrantUpdate
    data class Rejected(val code: String) : NormalizationGrantUpdate
}
interface TradingNormalizationComputeClient {
    fun submit(run: NormalizationRun, dispatch: NormalizationDispatch,
               grants: List<ArtifactReadGrant>): NormalizationSubmission
    fun status(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationStatusLookup
    fun updateGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                     current: NormalizationJobStatus, nextAttemptToken: String,
                     grants: List<ArtifactReadGrant>, updateKey: String): NormalizationGrantUpdate
}

/** Transport bytes stay outside durable state; the descriptor contains only safe opaque identities and digests. */
data class NormalizationResultDescriptor(
    val jobId: String,
    val logicalJobId: String,
    val runId: String,
    val payloadId: String,
    val byteSize: Long,
    val payloadSha256: String,
    val attemptToken: String,
    val deletionGeneration: Long,
    val inputSetHash: String,
    val inputHashSpecificationVersion: String,
    val targetVersionSet: Map<String, Any?>,
    val resultSchemaVersion: String,
    val resultHashSpecificationVersion: String,
    val counts: Map<String, Any?>,
    val sectionDigests: List<Any?>,
    val reconciliationManifestHash: String,
    val resultHash: String,
    val expiresAt: Instant,
)

sealed interface NormalizationResultLookup {
    data class Found(val descriptor: NormalizationResultDescriptor) : NormalizationResultLookup
    data class Retry(val code: String) : NormalizationResultLookup
    data class Rejected(val code: String) : NormalizationResultLookup
}

/** A temporary, exact HTTP entity body. Closing always removes it. */
class NormalizationPayloadFile(val path: Path, val byteSize: Long, val sha256: String) : AutoCloseable {
    fun open(): InputStream = Files.newInputStream(path)
    override fun close() { Files.deleteIfExists(path) }
}

sealed interface NormalizationPayloadLookup {
    data class Found(val payload: NormalizationPayloadFile) : NormalizationPayloadLookup
    data class Retry(val code: String) : NormalizationPayloadLookup
    data class Rejected(val code: String) : NormalizationPayloadLookup
}

data class NormalizationPayloadAcknowledgement(
    val runId: String,
    val coreImportSessionId: String,
    val jobId: String,
    val payloadId: String,
    val payloadSha256: String,
    val resultHash: String,
    val attemptToken: String,
    val deletionGeneration: Long,
    val idempotencyKey: String,
    val disposition: String,
    val occurredAt: Instant,
    val currentDeletionGeneration: Long? = null,
    val attemptCount: Int = 0,
    val retryPolicyJson: String,
    val payloadExpiresAt: Instant,
)

interface TradingNormalizationResultClient {
    fun descriptor(jobId: String): NormalizationResultLookup
    fun download(payloadId: String): NormalizationPayloadLookup
    fun acknowledge(acknowledgement: NormalizationPayloadAcknowledgement): NormalizationAckOutcome
}

sealed interface NormalizationAckOutcome {
    data object Accepted : NormalizationAckOutcome
    data object Retry : NormalizationAckOutcome
    data class Rejected(val code: String) : NormalizationAckOutcome
}

data class TradingNormalizationRetryPolicy(
    val version: String,
    val maxAttempts: Int,
    val maxElapsedTime: Duration,
    val initialDelay: Duration,
    val maxDelay: Duration,
    val jitter: String,
)

interface TradingRetryJitter { fun factor(): Double }

interface TradingNormalizationSettings {
    fun retryPolicy(): TradingNormalizationRetryPolicy
    fun instrumentTermsJson(): String
}

interface TradingNormalizationUseCase {
    fun validate(id: TradingImportSessionId, targetVersionSetJson: String?, key: String): TradingImportSession
    fun retry(id: TradingImportSessionId, key: String): TradingImportSession
}
