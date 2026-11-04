package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@Repository
class JpaTradingAccountStoreAdapter(
    private val jpaStore: TradingAccountJpaStore,
    private val entityManager: EntityManager,
) : TradingAccountStore {
    @Transactional
    override fun insert(aggregate: TradingAccount) {
        require(aggregate.version == 0L && aggregate.deletionGeneration == 0L && aggregate.status == TradingLifecycleStatus.ACTIVE) {
            "Only a new active TradingAccount can be inserted"
        }
        val entity = TradingAccountPersistenceMapper.toEntity(aggregate)
        entityManager.persist(entity)
        entityManager.flush()
        entityManager.detach(entity)
    }

    override fun find(id: TradingAccountId, owner: MemberId, forUpdate: Boolean): TradingAccount? {
        if (forUpdate) check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Command lock requires an enclosing transaction"
        }
        if (forUpdate && jpaStore.lockOwned(id.value, owner.value) == null) return null
        return jpaStore.readSnapshot(id.value, owner.value)?.let(TradingAccountPersistenceMapper::toDomain)
    }

    @Transactional
    override fun update(aggregate: TradingAccount): Boolean {
        if (jpaStore.lockOwned(aggregate.id.value, aggregate.owner.value) == null) return false
        val entity = jpaStore.findOwned(aggregate.id.value, aggregate.owner.value) ?: return false
        try {
            entityManager.refresh(entity)
            if (entity.version != aggregate.version || entity.venue != aggregate.venue.name ||
                entity.createdAt != aggregate.createdAt.atTradingStoredPrecision() ||
                entity.updatedAt > aggregate.updatedAt.atTradingStoredPrecision() ||
                !tradingLifecycleMatches(entity.status, entity.deletionGeneration, aggregate.status.name, aggregate.deletionGeneration)) return false
            TradingAccountPersistenceMapper.apply(aggregate, entity)
            entityManager.flushTradingSnapshot(entity, aggregate.version) { entity.version }
            return true
        } finally { entityManager.detach(entity) }
    }

    override fun deleted(id: TradingAccountId, owner: MemberId): Boolean = jpaStore.deleted(id.value, owner.value)
}
