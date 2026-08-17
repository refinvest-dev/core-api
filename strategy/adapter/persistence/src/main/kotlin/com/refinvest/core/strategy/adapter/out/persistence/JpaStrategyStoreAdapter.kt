package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.ConditionOperand
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.strategy.domain.valueobject.LogicalCombinator
import com.refinvest.core.strategy.domain.valueobject.MemberId
import com.refinvest.core.strategy.domain.valueobject.MetricOperand
import com.refinvest.core.strategy.domain.valueobject.MetricReference
import com.refinvest.core.strategy.domain.valueobject.MetricType
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
import com.refinvest.core.strategy.port.outbound.StrategyStore
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyStoreAdapter(
    private val strategyJpaStore: StrategyJpaStore,
) : StrategyStore {
    override fun findById(id: StrategyId): Strategy? =
        strategyJpaStore.findById(id.value).orElse(null)?.toDomain()

    override fun save(strategy: Strategy) {
        val entity = StrategyJpaEntity(
            id = strategy.id.value,
            memberId = strategy.memberId.value,
            name = strategy.name,
            createdAt = strategy.createdAt,
        )
        entity.replaceVersions(strategy.versions.map { it.toEntity(entity) })
        strategyJpaStore.save(entity)
    }

    private fun StrategyJpaEntity.toDomain(): Strategy = Strategy.create(
        id = StrategyId(id),
        memberId = MemberId(memberId),
        name = name,
        createdAt = createdAt,
    ).also { strategy -> versions.forEach { strategy.addVersion(it.toDomain()) } }

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
