package com.refinvest.core.tradingreview.port

import com.refinvest.core.tradingreview.domain.*
import java.io.InputStream
import java.time.Duration
import java.time.Instant

interface TradingNormalizationStore {
    fun insert(run: NormalizationRun, dispatch: NormalizationDispatch)
    fun findRun(id: String): NormalizationRun?
    fun findActive(sessionId: TradingImportSessionId): NormalizationRun?
    fun claimNext(now: Instant, lease: Duration, workerId: String): NormalizationDispatch?
    fun claimSubmitted(now: Instant, lease: Duration, workerId: String): NormalizationDispatch?
    fun schedulePoll(dispatchId: String, leaseOwner: String, next: Instant, now: Instant): Boolean
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
