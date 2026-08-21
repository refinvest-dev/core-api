package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.strategy.domain.valueobject.LogicalCombinator
import com.refinvest.core.strategy.domain.valueobject.MetricOperand
import com.refinvest.core.strategy.domain.valueobject.MetricReference
import com.refinvest.core.strategy.domain.valueobject.MetricType
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
import com.refinvest.core.strategy.port.outbound.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.StrategyReader
import com.refinvest.core.strategy.port.outbound.StrategyPageReadModel
import com.refinvest.core.strategy.port.outbound.StrategyVersionReadModel
import org.springframework.stereotype.Repository
import org.springframework.data.domain.PageRequest

@Repository
class JpaStrategyReaderAdapter(
    private val strategyJpaReader: StrategyJpaReader,
) : StrategyReader {
    override fun findById(id: StrategyId): StrategyReadModel? =
        strategyJpaReader.findById(id.value)?.toReadModel()

    override fun findByMemberId(
        memberId: MemberId,
        page: Int,
        size: Int,
    ): StrategyPageReadModel = strategyJpaReader
        .findAllByMemberIdOrderByCreatedAtDesc(memberId.value, PageRequest.of(page, size))
        .let { strategies -> StrategyPageReadModel(strategies.content.map { it.toReadModel() }, strategies.totalElements) }

    private fun StrategyJpaEntity.toReadModel(): StrategyReadModel {
        val versions = versions.map { it.toReadModel() }
        return StrategyReadModel(
            id = StrategyId(id),
            name = name,
            createdAt = createdAt,
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
