package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.ConditionOperand
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.strategy.domain.valueobject.LogicalCombinator
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.MetricOperand
import com.refinvest.core.strategy.domain.valueobject.MetricReference
import com.refinvest.core.strategy.domain.valueobject.MetricType
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
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
    override fun findById(id: StrategyId): Strategy? = loadLocked(id)?.toDomain()

    @Transactional
    override fun save(strategy: Strategy) {
        val existing = loadLocked(strategy.id)
        if (existing == null) {
            val entity = StrategyJpaEntity(strategy.id.value, strategy.memberId.value, strategy.name, strategy.createdAt)
            entity.versions += strategy.versions.map { it.toEntity(entity) }
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
                require(stored.toDomain().hasSameDefinitionAs(version)) { "Existing StrategyVersion cannot be replaced" }
            }
        }
        strategy.versions.filterNot { it.id.value in storedVersions }.forEach { version ->
            val entity = version.toEntity(existing)
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

    private fun StrategyJpaEntity.toDomain(): Strategy {
        val strategy = Strategy.create(
            id = StrategyId(id),
            memberId = MemberId(memberId),
            name = name,
            createdAt = createdAt,
        )
        versions.forEach { version -> strategy.addVersion(version.toDomain()) }
        return strategy
    }

    private fun StrategyVersionJpaEntity.toDomain(): StrategyVersion = StrategyVersion.create(
        id = StrategyVersionId(id),
        strategyId = StrategyId(strategy.id),
        createdAt = createdAt,
        primarySignalAsset = AssetSymbol.valueOf(primarySignalAsset),
        conditions = conditions.map { it.toDomain() },
        executionAsset = AssetSymbol.valueOf(executionAsset),
        lag = SignalSessions(lag),
        exit = TimeBasedExit(holdingSignalSessions),
    )

    private fun StrategyVersion.toEntity(strategy: StrategyJpaEntity): StrategyVersionJpaEntity {
        val entity = StrategyVersionJpaEntity(
            id = id.value,
            strategy = strategy,
            createdAt = createdAt,
            primarySignalAsset = primarySignalAsset.name,
            executionAsset = executionAsset.name,
            lag = lag.value,
            holdingSignalSessions = exit.holdingSignalSessions,
        )
        entity.conditions += conditions.mapIndexed { index, condition -> condition.toEntity(entity, index) }
        return entity
    }

    private fun ConditionJpaEntity.toDomain(): Condition = Condition(
        operator = ComparisonOperator.valueOf(operator),
        logicalCombinator = logicalCombinator?.let(LogicalCombinator::valueOf),
        operandA = MetricReference(AssetSymbol.valueOf(operandAAsset), MetricType.valueOf(operandAMetric), operandAWindow),
        operandB = when (operandBKind) {
            LITERAL -> LiteralValue(requireNotNull(operandBLiteral))
            METRIC -> MetricOperand(
                MetricReference(
                    AssetSymbol.valueOf(requireNotNull(operandBAsset)),
                    MetricType.valueOf(requireNotNull(operandBMetric)),
                    operandBWindow,
                ),
            )
            else -> error("Unsupported operand B kind: $operandBKind")
        },
    )

    private fun Condition.toEntity(
        strategyVersion: StrategyVersionJpaEntity,
        conditionOrder: Int,
    ): ConditionJpaEntity =
        when (val operandB = operandB) {
            is LiteralValue -> ConditionJpaEntity(
                strategyVersion = strategyVersion,
                conditionOrder = conditionOrder,
                operator = operator.name,
                logicalCombinator = logicalCombinator?.name,
                operandAAsset = operandA.asset.name,
                operandAMetric = operandA.metric.name,
                operandAWindow = operandA.window,
                operandBKind = LITERAL,
                operandBLiteral = operandB.value,
                operandBAsset = null,
                operandBMetric = null,
                operandBWindow = null,
            )
            is MetricOperand -> ConditionJpaEntity(
                strategyVersion = strategyVersion,
                conditionOrder = conditionOrder,
                operator = operator.name,
                logicalCombinator = logicalCombinator?.name,
                operandAAsset = operandA.asset.name,
                operandAMetric = operandA.metric.name,
                operandAWindow = operandA.window,
                operandBKind = METRIC,
                operandBLiteral = null,
                operandBAsset = operandB.reference.asset.name,
                operandBMetric = operandB.reference.metric.name,
                operandBWindow = operandB.reference.window,
            )
        }

    private companion object {
        const val LITERAL = "LITERAL"
        const val METRIC = "METRIC"
    }
}
