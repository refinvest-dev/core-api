package com.refinvest.core.auth.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refresh_sessions")
class RefreshSessionJpaEntity(
    @Id
    var jti: UUID,
    @Column(name = "member_id", nullable = false)
    var memberId: Long,
    @Column(name = "family_id", nullable = false)
    var familyId: UUID,
    @Column(name = "token_fingerprint", nullable = false, length = 128)
    var tokenFingerprint: String,
    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
    @Column(name = "replaced_by_jti")
    var replacedByJti: UUID? = null,
)
