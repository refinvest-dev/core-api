package com.refinvest.core.tradingreview.domain

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import java.time.ZoneId

@JvmInline value class TradingImportSessionId(val value: Long)
@JvmInline value class TradingSourceArtifactId(val value: Long)

enum class TradingImportStatus { RECEIVED, VALIDATING, ACCEPTED, REJECTED }
enum class TradingArtifactRole { TRADE_HISTORY, POSITION_HISTORY }
enum class TradingArtifactRetentionStatus { ACTIVE, RETENTION_SCHEDULED, DELETION_PENDING, DELETED, DELETION_FAILED }

data class TradingImportSession(
    val id: TradingImportSessionId,
    val bookId: TradingBookId,
    val owner: MemberId,
    val sourceTimezone: String,
    val reviewTimezone: String,
    val declaredPositionMode: TradingPositionMode,
    val dataPolicyVersion: String,
    val retentionPolicy: String,
    val maskingPolicyVersion: String,
    val evidenceRetention: String,
    val deletionPolicyVersion: String,
    val versionSetJson: String,
    val status: TradingImportStatus,
    val deletionGeneration: Long,
    val deletionPending: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    fun canUpload() = status == TradingImportStatus.RECEIVED && !deletionPending

    fun missingArtifactRoles(artifacts: List<TradingSourceArtifact>): Set<TradingArtifactRole> =
        TradingArtifactRole.entries.toSet() - artifacts.filter { it.retentionStatus == TradingArtifactRetentionStatus.ACTIVE }
            .map { it.role }.toSet()

    fun canValidate(artifacts: List<TradingSourceArtifact>) = canUpload() && missingArtifactRoles(artifacts).isEmpty()

    fun requestRawDeletion(now: Instant): TradingImportSession {
        require(!deletionPending) { "Session deletion already requested" }
        return copy(deletionGeneration = deletionGeneration + 1, updatedAt = now)
    }

    fun requestDeletion(now: Instant): TradingImportSession {
        require(!deletionPending) { "Deletion already requested" }
        return copy(deletionGeneration = deletionGeneration + 1, deletionPending = true, updatedAt = now)
    }

    companion object {
        fun create(id: TradingImportSessionId, book: TradingBook, sourceTimezone: String,
                   reviewTimezone: String, mode: TradingPositionMode, policy: TradingDataPolicy,
                   versionSetJson: String, now: Instant): TradingImportSession {
            require(book.status == TradingLifecycleStatus.ACTIVE) { "Book is not active" }
            require(sourceTimezone in ZoneId.getAvailableZoneIds()) { "Invalid source timezone" }
            require(reviewTimezone in ZoneId.getAvailableZoneIds()) { "Invalid review timezone" }
            require(mode == TradingPositionMode.ONE_WAY && mode == book.positionMode) { "Unsupported position mode" }
            require(versionSetJson.isNotBlank()) { "Exact version set is required" }
            return TradingImportSession(id, book.id, book.owner, sourceTimezone, reviewTimezone, mode,
                policy.dataPolicyVersion, policy.retentionPolicy, policy.maskingPolicyVersion,
                "BOOK_LIFECYCLE", policy.deletionPolicyVersion, versionSetJson,
                TradingImportStatus.RECEIVED, 0, false, now, now, 0)
        }
    }
}

data class TradingDataPolicy(
    val dataPolicyVersion: String = "TRADING_DATA_POLICY_V1",
    val retentionPolicy: String = "TRADING_RETENTION_V1",
    val maskingPolicyVersion: String = "SOURCE_EVIDENCE_MASK_V1",
    val deletionPolicyVersion: String = "TRADING_DELETION_V1",
)

data class TradingSourceArtifact(
    val id: TradingSourceArtifactId,
    val sessionId: TradingImportSessionId,
    val role: TradingArtifactRole,
    val filename: String?,
    val contentType: String,
    val byteSize: Long,
    val sha256: String,
    val dialectCandidate: String,
    val objectKey: String?,
    val wrappedDek: ByteArray?,
    val keyVersion: String?,
    val retentionStatus: TradingArtifactRetentionStatus,
    val uploadedAt: Instant,
    val rawExpiresAt: Instant,
    val deletedAt: Instant?,
    val deletionFailure: String?,
    val version: Long,
) {
    fun rawReparseAvailable(now: Instant) = retentionStatus == TradingArtifactRetentionStatus.ACTIVE &&
        now.isBefore(rawExpiresAt) && objectKey != null && wrappedDek != null

    override fun equals(other: Any?): Boolean = other is TradingSourceArtifact && id == other.id && version == other.version
    override fun hashCode(): Int = 31 * id.hashCode() + version.hashCode()
}
