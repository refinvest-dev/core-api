package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.persistence.StrategyVersionReadModel
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReadModel
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyConditionForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyLiteralOperandForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyMetricOperandForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyMetricReferenceForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyVersionForBacktest

internal object StrategyReadModelMapper {
    fun toReadModel(entity: StrategyJpaEntity): StrategyReadModel {
        val versions = entity.versions.sortedWith(compareBy<StrategyVersionJpaEntity> { it.createdAt }.thenBy { it.id })
            .map { toVersionReadModel(it) }
        return StrategyReadModel(StrategyId(entity.id), MemberId(entity.memberId), entity.name, entity.createdAt,
            versions.lastOrNull()?.id, versions)
    }

    private fun toVersionReadModel(entity: StrategyVersionJpaEntity): StrategyVersionReadModel = StrategyVersionReadModel(
        StrategyVersionId(entity.id), entity.createdAt, AssetSymbol.valueOf(entity.primarySignalAsset),
        StrategyPersistenceMapper.orderedConditions(entity.conditions).map(StrategyPersistenceMapper::toDomain),
        AssetSymbol.valueOf(entity.executionAsset), SignalSessions(entity.lag), TimeBasedExit(entity.holdingSignalSessions))

    // Owner metadata is loaded by the adapter before mapping; do not navigate a lazy parent here.
    fun toBacktestReadModel(version: StrategyVersionJpaEntity, strategyId: Long, ownerMemberId: MemberId,
                            conditions: List<ConditionJpaEntity>): StrategyVersionBacktestReadModel =
        StrategyVersionBacktestReadModel(
            strategyId = strategyId,
            ownerMemberId = ownerMemberId,
            assetSymbols = buildSet {
                add(version.primarySignalAsset)
                add(version.executionAsset)
                conditions.forEach { condition -> add(condition.operandAAsset); condition.operandBAsset?.let(::add) }
            },
            definition = StrategyVersionForBacktest(
                version.primarySignalAsset,
                StrategyPersistenceMapper.orderedConditions(conditions).map { toBacktestCondition(it) },
                version.executionAsset, version.lag, version.holdingSignalSessions))

    private fun toBacktestCondition(entity: ConditionJpaEntity): StrategyConditionForBacktest = with(entity) {
        StrategyConditionForBacktest(operator, logicalCombinator,
            StrategyMetricReferenceForBacktest(operandAAsset, operandAMetric, operandAWindow),
            when (operandBKind) {
                "LITERAL" -> StrategyLiteralOperandForBacktest(requireNotNull(operandBLiteral))
                "METRIC" -> StrategyMetricOperandForBacktest(StrategyMetricReferenceForBacktest(
                    requireNotNull(operandBAsset), requireNotNull(operandBMetric), operandBWindow))
                else -> error("Unsupported operand B kind: $operandBKind")
            })
    }
}
