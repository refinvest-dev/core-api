package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.domain.valueobject.RefreshSessionRotation
import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Repository
class JpaRefreshSessionStoreAdapter(
    private val refreshSessionJpaStore: RefreshSessionJpaStore,
    private val familyLocks: RefreshSessionFamilyLockJpaStore,
    private val entityManager: EntityManager,
) : RefreshSessionStore {
    override fun findByJti(jti: UUID): RefreshSession? = refreshSessionJpaStore.findById(jti).orElse(null)
        ?.let(RefreshSessionPersistenceMapper::toDomain)

    @Transactional
    override fun save(session: RefreshSession) {
        require(session.revokedAt == null) { "New credentials must not already be revoked" }
        check(lockFamily(session.familyId).revokedAt == null) { "Cannot append to a revoked refresh family" }
        // Assigned JTIs are immutable: a collision must fail, never merge over an existing credential.
        entityManager.persist(RefreshSessionPersistenceMapper.toEntity(session))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun rotate(rotation: RefreshSessionRotation): Boolean {
        if (lockFamily(rotation.expected.familyId).revokedAt != null) return false
        val entity = refreshSessionJpaStore.findById(rotation.expected.jti).orElse(null) ?: return false
        // A previous unlocked lookup may already be in the persistence context. A lock query alone
        // does not reload that managed state after waiting for another writer.
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)
        if (!RefreshSessionPersistenceMapper.toDomain(entity).hasSameStateAs(rotation.expected)) return false
        entityManager.merge(RefreshSessionPersistenceMapper.toEntity(rotation.rotated))
        entityManager.persist(RefreshSessionPersistenceMapper.toEntity(rotation.replacement))
        return true
    }

    @Transactional
    override fun revokeFamily(revocation: RefreshFamilyRevocation) {
        val fence = lockFamily(revocation.familyId)
        if (fence.revokedAt == null) fence.revokedAt = revocation.revokedAt
        // The family gate excludes appends; bulk revocation does not load unbounded history.
        refreshSessionJpaStore.revokeActiveFamily(revocation.familyId, revocation.revokedAt)
    }

    private fun lockFamily(familyId: UUID): RefreshSessionFamilyLockJpaEntity {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Refresh writes require a transaction" }
        familyLocks.createIfMissing(familyId)
        return checkNotNull(familyLocks.lockById(familyId))
    }
}
