package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "trading_source_artifacts")
class TradingSourceArtifactJpaEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long,
    @Column(name = "import_session_id", nullable = false, updatable = false)
    var sessionId: Long,
    @Column(name = "artifact_role", nullable = false, updatable = false, length = 30)
    var role: String,
    @Column(name = "filename", nullable = true, updatable = false, length = 255)
    var filename: String?,
    @Column(name = "content_type", nullable = false, updatable = false, length = 50)
    var contentType: String,
    @Column(name = "byte_size", nullable = false, updatable = false)
    var byteSize: Long,
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, updatable = false, length = 64)
    var sha256: String,
    @Column(name = "dialect_candidate", nullable = false, updatable = false, length = 80)
    var dialectCandidate: String,
    @Column(name = "object_key", nullable = true, updatable = false, length = 160)
    var objectKey: String?,
    @Column(name = "wrapped_dek", nullable = true, updatable = false)
    var wrappedDek: ByteArray?,
    @Column(name = "key_version", nullable = true, updatable = false, length = 80)
    var keyVersion: String?,
    @Column(name = "retention_status", nullable = false, length = 30)
    var retentionStatus: String,
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    var uploadedAt: Instant,
    @Column(name = "raw_expires_at", nullable = false)
    var rawExpiresAt: Instant,
    @Column(name = "deleted_at", nullable = true, updatable = false)
    var deletedAt: Instant?,
    @Column(name = "deletion_failure", nullable = true, updatable = false, length = 80)
    var deletionFailure: String?,
    @Version
    @Column(name = "version", nullable = false)
    var version: Long,
    @Column(name = "upload_status", nullable = false, updatable = false, length = 20)
    var uploadStatus: String = "UPLOADED",
)
