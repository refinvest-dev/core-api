package com.refinvest.core.auth.domain

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import java.util.UUID

data class RefreshSession(
    val jti: UUID,
    val memberId: MemberId,
    val familyId: UUID,
    val tokenFingerprint: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val replacedByJti: UUID? = null,
) {
    fun isActive(at: Instant): Boolean = revokedAt == null && expiresAt.isAfter(at)
}
