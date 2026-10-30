package com.refinvest.core.tradingreview.domain.importsession

import com.refinvest.core.common.domain.DomainEntity
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

class TradingSourceArtifact private constructor(
    id: TradingSourceArtifactId,
    val sessionId: TradingImportSessionId,
    val role: TradingArtifactRole,
    val filename: String?,
    val contentType: String,
    val byteSize: Long,
    val sha256: String,
    val dialectCandidate: String,
    val objectKey: String?,
    wrappedDek: ByteArray?,
    val keyVersion: String?,
    val retentionStatus: TradingArtifactRetentionStatus,
    val uploadedAt: Instant,
    val rawExpiresAt: Instant,
    val deletedAt: Instant?,
    val deletionFailure: String?,
    val version: Long,
) : DomainEntity<TradingSourceArtifactId>(id) {
    private val encryptedKey = wrappedDek?.copyOf()
    val wrappedDek: ByteArray? get() = encryptedKey?.copyOf()

    init {
        require(version >= 0 && byteSize in 1..52_428_800) { "Invalid artifact version or size" }
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "Invalid raw SHA-256" }
        require(contentType in setOf("text/csv", "application/vnd.ms-excel") && dialectCandidate in setOf("AUTO",
            if (role == TradingArtifactRole.TRADE_HISTORY) "BINANCE_USDS_TRADE_HISTORY_V1" else "BINANCE_USDS_POSITION_HISTORY_V1")) { "Invalid artifact metadata" }
        require(rawExpiresAt.isAfter(uploadedAt)) { "Invalid raw retention deadline" }
        require((objectKey == null) == (encryptedKey == null) && (objectKey == null) == (keyVersion == null)) {
            "Incomplete raw descriptor"
        }
        require(objectKey == null || (objectKey.isNotBlank() && !encryptedKey!!.isEmpty() && keyVersion!!.isNotBlank()))
        require(if (retentionStatus == TradingArtifactRetentionStatus.DELETED)
            objectKey == null && deletedAt != null else objectKey != null && deletedAt == null) { "Invalid deletion state" }
    }

    fun rawReparseAvailable(now: Instant) = retentionStatus == TradingArtifactRetentionStatus.ACTIVE &&
        now.isBefore(rawExpiresAt) && objectKey != null && encryptedKey != null && keyVersion != null

    internal fun requestDeletion(): TradingSourceArtifact {
        require(retentionStatus in setOf(TradingArtifactRetentionStatus.ACTIVE, TradingArtifactRetentionStatus.RETENTION_SCHEDULED))
        return restore(id, sessionId, role, filename, contentType, byteSize, sha256, dialectCandidate,
            objectKey, encryptedKey, keyVersion, TradingArtifactRetentionStatus.DELETION_PENDING,
            uploadedAt, rawExpiresAt, null, deletionFailure, version)
    }

    override fun equals(other: Any?): Boolean = other is TradingSourceArtifact && id == other.id && version == other.version
    override fun hashCode(): Int = 31 * id.hashCode() + version.hashCode()

    companion object {
        fun restore(
            id: TradingSourceArtifactId,
            sessionId: TradingImportSessionId,
            role: TradingArtifactRole,
            filename: String?,
            contentType: String,
            byteSize: Long,
            sha256: String,
            dialectCandidate: String,
            objectKey: String?,
            wrappedDek: ByteArray?,
            keyVersion: String?,
            retentionStatus: TradingArtifactRetentionStatus,
            uploadedAt: Instant,
            rawExpiresAt: Instant,
            deletedAt: Instant?,
            deletionFailure: String?,
            version: Long,
        ) = TradingSourceArtifact(
            id, sessionId, role, filename, contentType, byteSize, sha256, dialectCandidate, objectKey,
            wrappedDek, keyVersion, retentionStatus, uploadedAt, rawExpiresAt, deletedAt, deletionFailure, version)
    }
}
