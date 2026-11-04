package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@Repository
class JpaTradingBookStoreAdapter(
    private val jpaStore: TradingBookJpaStore,
    private val accounts: TradingAccountJpaStore,
    private val entityManager: EntityManager,
) : TradingBookStore {
    @Transactional
    override fun insert(aggregate: TradingBook) {
        require(aggregate.version == 0L && aggregate.deletionGeneration == 0L && aggregate.status == TradingLifecycleStatus.ACTIVE) {
            "Only a new active TradingBook can be inserted"
        }
        require(parentActive(aggregate)) { "Book requires an active account with matching ownership and venue" }
        val entity = TradingBookPersistenceMapper.toEntity(aggregate)
        entityManager.persist(entity)
        entityManager.flush()
        entityManager.detach(entity)
    }

    override fun find(id: TradingBookId, owner: MemberId, forUpdate: Boolean): TradingBook? {
        if (forUpdate) check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Command lock requires an enclosing transaction"
        }
        if (forUpdate && jpaStore.lockOwned(id.value, owner.value) == null) return null
        return jpaStore.readSnapshot(id.value, owner.value)?.let(TradingBookPersistenceMapper::toDomain)
    }

    @Transactional
    override fun update(aggregate: TradingBook): Boolean {
        if (!parentActive(aggregate)) return false
        if (jpaStore.lockOwned(aggregate.id.value, aggregate.owner.value) == null) return false
        val entity = jpaStore.findOwned(aggregate.id.value, aggregate.owner.value) ?: return false
        try {
            entityManager.refresh(entity)
            if (entity.version != aggregate.version || entity.accountId != aggregate.accountId.value ||
                entity.venue != aggregate.venue.name || entity.productFamily != aggregate.productFamily.name ||
                entity.positionMode != aggregate.positionMode.name || entity.settlementAsset != aggregate.settlementAsset.name ||
                entity.reviewTimezone != aggregate.reviewTimezone || entity.latestLedgerRevisionId != aggregate.latestLedgerRevisionId?.value ||
                entity.createdAt != aggregate.createdAt.atTradingStoredPrecision() ||
                entity.updatedAt > aggregate.updatedAt.atTradingStoredPrecision() ||
                !tradingLifecycleMatches(entity.status, entity.deletionGeneration, aggregate.status.name, aggregate.deletionGeneration)) return false
            TradingBookPersistenceMapper.apply(aggregate, entity)
            entityManager.flushTradingSnapshot(entity, aggregate.version) { entity.version }
            return true
        } finally { entityManager.detach(entity) }
    }

    private fun parentActive(aggregate: TradingBook): Boolean {
        if (accounts.lockOwned(aggregate.accountId.value, aggregate.owner.value) == null) return false
        val parent = accounts.findOwned(aggregate.accountId.value, aggregate.owner.value) ?: return false
        return try {
            entityManager.refresh(parent)
            parent.status == "ACTIVE" && parent.venue == aggregate.venue.name
        } finally { entityManager.detach(parent) }
    }

    override fun deleted(id: TradingBookId, owner: MemberId): Boolean = jpaStore.deleted(id.value, owner.value)
}
