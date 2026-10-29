package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.outbound.persistence.StrategyStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.temporal.ChronoUnit

@Repository
class JpaStrategyStoreAdapter(
    private val strategyJpaStore: StrategyJpaStore,
    private val entityManager: EntityManager,
) : StrategyStore {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun findById(id: StrategyId): Strategy? = loadLocked(id)?.let(StrategyPersistenceMapper::toDomain)

    @Transactional
    override fun save(strategy: Strategy) {
        val existing = loadLocked(strategy.id)
        if (existing == null) {
            val entity = StrategyPersistenceMapper.toEntity(strategy)
            entityManager.persist(entity)
            return
        }

        require(existing.memberId == strategy.memberId.value && existing.name == strategy.name &&
            existing.createdAt.atStoredPrecision() == strategy.createdAt.atStoredPrecision()) {
            "Existing Strategy metadata cannot be replaced"
        }
        val storedVersions = existing.versions.associateBy { it.id }
        strategy.versions.forEach { version ->
            storedVersions[version.id.value]?.let { stored ->
                require(StrategyPersistenceMapper.toDomain(stored, strategy.id).hasSameDefinitionAs(version)) { "Existing StrategyVersion cannot be replaced" }
            }
        }
        strategy.versions.filterNot { it.id.value in storedVersions }.forEach { version ->
            val entity = StrategyPersistenceMapper.toEntity(version, existing)
            existing.versions += entity
            // Persist rather than merge: an ID owned by another Strategy must fail, never move or overwrite it.
            entityManager.persist(entity)
        }
    }

    private fun loadLocked(id: StrategyId): StrategyJpaEntity? {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Strategy command access requires a transaction" }
        strategyJpaStore.lockById(id.value) ?: return null
        return strategyJpaStore.findById(id.value).orElse(null)
    }

    private fun StrategyVersion.hasSameDefinitionAs(other: StrategyVersion): Boolean =
        strategyId == other.strategyId && createdAt.atStoredPrecision() == other.createdAt.atStoredPrecision() &&
            primarySignalAsset == other.primarySignalAsset && conditions == other.conditions &&
            executionAsset == other.executionAsset && lag == other.lag && exit == other.exit

    // PostgreSQL timestamps round Clock nanoseconds to the existing columns' microsecond precision.
    private fun Instant.atStoredPrecision(): Instant = plusNanos(500).truncatedTo(ChronoUnit.MICROS)

}
