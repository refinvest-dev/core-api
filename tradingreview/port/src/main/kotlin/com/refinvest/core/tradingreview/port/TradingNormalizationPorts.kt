package com.refinvest.core.tradingreview.port

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    data class Retry(val code: String, val retryAfter: Duration? = null, val outcomeUnknown: Boolean = false) : NormalizationSubmission
    data class Rejected(val code: String) : NormalizationSubmission
}

data class NormalizationJobStatus(
    val jobId: String, val state: String, val attemptToken: String,
    val deletionGeneration: Long, val grantRevision: Long,
    val expectedInputSetHash: String, val versionSetHash: String,
    val grantAction: String, val grantRefreshDeadlineAt: Instant?,
    val failureCode: String?,
    val resultVersionPin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
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
    val resultDeliveryPolicyVersion: String,
    val contentEncoding: String,
)

sealed interface NormalizationResultLookup {
    data class Found(val descriptor: NormalizationResultDescriptor) : NormalizationResultLookup
    data class Retry(val code: String) : NormalizationResultLookup
    data class Rejected(val code: String) : NormalizationResultLookup
}

/** A temporary HTTP entity body. Production downloads use encrypted spool bytes. */
class NormalizationPayloadFile(
    val path: Path, val byteSize: Long, val sha256: String,
    private val spoolKey: ByteArray? = null,
    private val spoolIv: ByteArray? = null,
) : AutoCloseable {
    fun open(): InputStream {
        val key = spoolKey ?: return Files.newInputStream(path)
        val iv = requireNotNull(spoolIv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return CipherInputStream(Files.newInputStream(path), cipher)
    }
    override fun close() {
        try { Files.deleteIfExists(path) }
        finally {
            spoolKey?.fill(0)
            spoolIv?.fill(0)
        }
    }
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
    fun download(descriptor: NormalizationResultDescriptor,
                 policy: NormalizationResultDeliveryPolicy): NormalizationPayloadLookup
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
    fun resultDeliveryPolicy(): NormalizationResultDeliveryPolicy
}

interface TradingNormalizationUseCase {
    fun validate(id: TradingImportSessionId, targetVersionSetJson: String?, key: String): TradingImportSession
    fun retry(id: TradingImportSessionId, key: String): TradingImportSession
}
