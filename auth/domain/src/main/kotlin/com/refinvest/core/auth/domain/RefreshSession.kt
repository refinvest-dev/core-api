package com.refinvest.core.auth.domain

import com.refinvest.core.auth.domain.valueobject.RefreshSessionId
import com.refinvest.core.auth.domain.valueobject.RefreshSessionRotation
import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import java.util.UUID

class RefreshSession private constructor(
    id: RefreshSessionId,
    val memberId: MemberId,
    val familyId: UUID,
    val tokenFingerprint: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val replacedByJti: UUID?,
) : AggregateRoot<RefreshSessionId>(id) {
    val jti: UUID get() = id.value

    init {
        require(tokenFingerprint.isNotBlank()) { "tokenFingerprint must not be blank" }
        require(expiresAt.isAfter(issuedAt)) { "Refresh expiry must follow issuance" }
        require(replacedByJti == null || (revokedAt != null && replacedByJti != jti)) { "Replacement requires a revoked, distinct credential" }
    }

    fun isActive(at: Instant): Boolean = revokedAt == null && !issuedAt.isAfter(at) && expiresAt.isAfter(at)

    fun accepts(memberId: MemberId, familyId: UUID, fingerprint: String, at: Instant): Boolean =
        this.memberId == memberId && this.familyId == familyId && tokenFingerprint == fingerprint && isActive(at)

    fun rotate(replacement: RefreshSession, at: Instant): RefreshSessionRotation {
        check(isActive(at)) { "Only an active refresh credential can rotate" }
        require(replacement.jti != jti && replacement.memberId == memberId && replacement.familyId == familyId) {
            "Replacement must use a new JTI within the same member and family"
        }
        require(replacement.revokedAt == null && replacement.expiresAt.isAfter(at)) { "Replacement must be usable" }
        val rotated = RefreshSession(id, memberId, familyId, tokenFingerprint, issuedAt, expiresAt, at, replacement.jti)
        return RefreshSessionRotation(this, rotated, replacement)
    }

    fun revoke(at: Instant): RefreshSession = if (revokedAt != null) this else
        RefreshSession(id, memberId, familyId, tokenFingerprint, issuedAt, expiresAt, at, replacedByJti)

    fun hasSameStateAs(other: RefreshSession): Boolean = id == other.id && memberId == other.memberId &&
        familyId == other.familyId && tokenFingerprint == other.tokenFingerprint && issuedAt == other.issuedAt &&
        expiresAt == other.expiresAt && revokedAt == other.revokedAt && replacedByJti == other.replacedByJti

    companion object {
        fun create(jti: UUID, memberId: MemberId, familyId: UUID, tokenFingerprint: String,
                   issuedAt: Instant, expiresAt: Instant): RefreshSession =
            RefreshSession(RefreshSessionId(jti), memberId, familyId, tokenFingerprint, issuedAt, expiresAt, null, null)

        fun restore(jti: UUID, memberId: MemberId, familyId: UUID, tokenFingerprint: String,
                    issuedAt: Instant, expiresAt: Instant, revokedAt: Instant?, replacedByJti: UUID?): RefreshSession =
            RefreshSession(RefreshSessionId(jti), memberId, familyId, tokenFingerprint, issuedAt, expiresAt, revokedAt, replacedByJti)
    }
}
