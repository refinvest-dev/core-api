package com.refinvest.core.auth.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Technical fence for the atomic family capability, not a separate domain aggregate. */
@Entity
@Table(name = "refresh_session_family_locks")
class RefreshSessionFamilyLockJpaEntity(
    @Id @Column(name = "family_id") val familyId: UUID,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
)
