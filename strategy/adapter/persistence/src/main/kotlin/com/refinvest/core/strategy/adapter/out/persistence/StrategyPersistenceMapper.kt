package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
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

internal object StrategyPersistenceMapper {
    fun toEntity(strategy: Strategy): StrategyJpaEntity {
        val entity = StrategyJpaEntity(strategy.id.value, strategy.memberId.value, strategy.name, strategy.createdAt)
        entity.versions += strategy.versions.map { toEntity(it, entity) }
        return entity
    }

    fun toDomain(entity: StrategyJpaEntity): Strategy = Strategy.restore(
        StrategyId(entity.id), MemberId(entity.memberId), entity.name, entity.createdAt,
        entity.versions.map { toDomain(it, StrategyId(it.strategy.id)) })

    fun toDomain(entity: StrategyVersionJpaEntity, strategyId: StrategyId): StrategyVersion = StrategyVersion.restore(
        StrategyVersionId(entity.id), strategyId, entity.createdAt, AssetSymbol.valueOf(entity.primarySignalAsset),
        orderedConditions(entity.conditions).map { toDomain(it) }, AssetSymbol.valueOf(entity.executionAsset),
        SignalSessions(entity.lag), TimeBasedExit(entity.holdingSignalSessions))

    fun toEntity(version: StrategyVersion, parent: StrategyJpaEntity): StrategyVersionJpaEntity {
        require(version.strategyId.value == parent.id) { "StrategyVersion belongs to another Strategy" }
        val entity = StrategyVersionJpaEntity(version.id.value, parent, version.createdAt, version.primarySignalAsset.name,
            version.executionAsset.name, version.lag.value, version.exit.holdingSignalSessions)
        entity.conditions += version.conditions.mapIndexed { index, condition -> condition.toEntity(entity, index) }
        return entity
    }

    fun orderedConditions(conditions: List<ConditionJpaEntity>): List<ConditionJpaEntity> = conditions.sortedBy { it.conditionOrder }

    fun toDomain(entity: ConditionJpaEntity): Condition = with(entity) {
        Condition(
            operator = ComparisonOperator.valueOf(operator),
            logicalCombinator = logicalCombinator?.let(LogicalCombinator::valueOf),
            operandA = MetricReference(AssetSymbol.valueOf(operandAAsset), MetricType.valueOf(operandAMetric), operandAWindow),
            operandB = when (operandBKind) {
                LITERAL -> LiteralValue(requireNotNull(operandBLiteral))
                METRIC -> MetricOperand(MetricReference(AssetSymbol.valueOf(requireNotNull(operandBAsset)),
                    MetricType.valueOf(requireNotNull(operandBMetric)), operandBWindow))
                else -> error("Unsupported operand B kind: $operandBKind")
            })
    }

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

    private const val LITERAL = "LITERAL"
    private const val METRIC = "METRIC"
}
