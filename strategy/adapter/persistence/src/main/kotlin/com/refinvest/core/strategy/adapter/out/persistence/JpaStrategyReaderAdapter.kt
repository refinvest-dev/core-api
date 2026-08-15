package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.StrategyVersionId
import com.refinvest.core.strategy.domain.AssetSymbol
import com.refinvest.core.strategy.domain.ComparisonOperator
import com.refinvest.core.strategy.domain.Condition
import com.refinvest.core.strategy.domain.LiteralValue
import com.refinvest.core.strategy.domain.LogicalCombinator
import com.refinvest.core.strategy.domain.MetricOperand
import com.refinvest.core.strategy.domain.MetricReference
import com.refinvest.core.strategy.domain.MetricType
import com.refinvest.core.strategy.domain.SignalSessions
import com.refinvest.core.strategy.domain.TimeBasedExit
import com.refinvest.core.strategy.port.outbound.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.StrategyReader
import com.refinvest.core.strategy.port.outbound.StrategyVersionReadModel
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyReaderAdapter(
    private val strategyJpaReader: StrategyJpaReader,
) : StrategyReader {
    override fun findById(id: StrategyId): StrategyReadModel? =
        strategyJpaReader.findById(id.value)?.let { strategy ->
            val versions = strategy.versions.map { it.toReadModel() }
            StrategyReadModel(
                id = StrategyId(strategy.id),
                name = strategy.name,
                createdAt = strategy.createdAt,
                latestVersionId = versions.lastOrNull()?.id,
                versions = versions,
            )
        }

    private fun StrategyVersionJpaEntity.toReadModel(): StrategyVersionReadModel = StrategyVersionReadModel(
        id = StrategyVersionId(id),
        createdAt = createdAt,
        primarySignalAsset = AssetSymbol.valueOf(primarySignalAsset),
        conditions = conditions.map { it.toDomain() },
        executionAsset = AssetSymbol.valueOf(executionAsset),
        lag = SignalSessions(lag),
        exit = TimeBasedExit(holdingSignalSessions),
    )

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

    private companion object {
        const val LITERAL = "LITERAL"
        const val METRIC = "METRIC"
    }
}
