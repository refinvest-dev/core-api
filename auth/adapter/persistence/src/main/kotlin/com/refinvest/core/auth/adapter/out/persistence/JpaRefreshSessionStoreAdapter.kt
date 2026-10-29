package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.domain.valueobject.RefreshSessionRotation
import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class JpaRefreshSessionStoreAdapter(private val refreshSessionJpaStore: RefreshSessionJpaStore) : RefreshSessionStore {
    override fun findByJti(jti: UUID): RefreshSession? = refreshSessionJpaStore.findById(jti).orElse(null)
        ?.let(RefreshSessionPersistenceMapper::toDomain)

    override fun save(session: RefreshSession) {
        refreshSessionJpaStore.save(RefreshSessionPersistenceMapper.toEntity(session))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun rotate(rotation: RefreshSessionRotation): Boolean {
        val entity = refreshSessionJpaStore.findByIdForUpdate(rotation.expected.jti) ?: return false
        if (!RefreshSessionPersistenceMapper.toDomain(entity).hasSameStateAs(rotation.expected)) return false
        refreshSessionJpaStore.save(RefreshSessionPersistenceMapper.toEntity(rotation.rotated))
        refreshSessionJpaStore.save(RefreshSessionPersistenceMapper.toEntity(rotation.replacement))
        return true
    }

    @Transactional
    override fun revokeFamily(revocation: RefreshFamilyRevocation) {
        refreshSessionJpaStore.findAllByFamilyIdAndRevokedAtIsNull(revocation.familyId).forEach { entity ->
            entity.revokedAt = RefreshSessionPersistenceMapper.toDomain(entity).revoke(revocation.revokedAt).revokedAt
        }
    }
}
