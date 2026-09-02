package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReadModel
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReader
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyConditionForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyConditionOperandForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyLiteralOperandForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyMetricOperandForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyMetricReferenceForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyVersionForBacktest
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyVersionBacktestReaderAdapter(
    private val strategyVersionJpaReader: StrategyVersionJpaReader,
) : StrategyVersionBacktestReader {
    override fun findById(strategyVersionId: Long): StrategyVersionBacktestReadModel? =
        strategyVersionJpaReader.findById(strategyVersionId)?.let { version ->
            StrategyVersionBacktestReadModel(
                strategyId = version.strategy.id,
                ownerMemberId = MemberId(version.strategy.memberId),
                assetSymbols = buildSet {
                    add(version.primarySignalAsset)
                    add(version.executionAsset)
                    version.conditions.forEach { condition ->
                        add(condition.operandAAsset)
                        condition.operandBAsset?.let(::add)
                    }
                },
                definition = StrategyVersionForBacktest(
                    primarySignalAsset = version.primarySignalAsset,
                    conditions = version.conditions.map { condition -> condition.toBacktestCondition() },
                    executionAsset = version.executionAsset,
                    lag = version.lag,
                    holdingSignalSessions = version.holdingSignalSessions,
                ),
            )
        }

    private fun ConditionJpaEntity.toBacktestCondition(): StrategyConditionForBacktest = StrategyConditionForBacktest(
        operator = operator,
        logicalCombinator = logicalCombinator,
        operandA = StrategyMetricReferenceForBacktest(operandAAsset, operandAMetric, operandAWindow),
        operandB = when (operandBKind) {
            "LITERAL" -> StrategyLiteralOperandForBacktest(requireNotNull(operandBLiteral))
            "METRIC" -> StrategyMetricOperandForBacktest(
                StrategyMetricReferenceForBacktest(
                    requireNotNull(operandBAsset),
                    requireNotNull(operandBMetric),
                    operandBWindow,
                ),
            )
            else -> error("Unsupported operand B kind: $operandBKind")
        },
    )
}
