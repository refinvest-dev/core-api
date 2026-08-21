package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.port.outbound.RefreshSessionStore
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JpaRefreshSessionStoreAdapter(
    private val refreshSessionJpaStore: RefreshSessionJpaStore,
) : RefreshSessionStore {
    override fun findByJti(jti: UUID): RefreshSession? = refreshSessionJpaStore.findById(jti).orElse(null)?.toDomain()

    override fun save(session: RefreshSession) {
        refreshSessionJpaStore.save(session.toEntity())
    }

    override fun rotate(current: RefreshSession, replacement: RefreshSession, revokedAt: Instant): Boolean {
        val entity = refreshSessionJpaStore.findByIdForUpdate(current.jti) ?: return false
        if (entity.revokedAt != null) return false
        entity.revokedAt = revokedAt
        entity.replacedByJti = replacement.jti
        refreshSessionJpaStore.save(entity)
        refreshSessionJpaStore.save(replacement.toEntity())
        return true
    }

    override fun revokeFamily(familyId: UUID, revokedAt: Instant) {
        refreshSessionJpaStore.findAllByFamilyIdAndRevokedAtIsNull(familyId).forEach { it.revokedAt = revokedAt }
    }

    private fun RefreshSessionJpaEntity.toDomain() = RefreshSession(
        jti = jti,
        memberId = MemberId(memberId),
        familyId = familyId,
        tokenFingerprint = tokenFingerprint,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        replacedByJti = replacedByJti,
    )

    private fun RefreshSession.toEntity() = RefreshSessionJpaEntity(
        jti = jti,
        memberId = memberId.value,
        familyId = familyId,
        tokenFingerprint = tokenFingerprint,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        replacedByJti = replacedByJti,
    )
}
