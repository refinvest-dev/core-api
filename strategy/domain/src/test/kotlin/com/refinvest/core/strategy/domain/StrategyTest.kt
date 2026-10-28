package com.refinvest.core.strategy.domain

import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.MetricReference
import com.refinvest.core.strategy.domain.valueobject.MetricType
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyTest {
    @Test
    fun `rejects duplicate version identity without changing the aggregate`() {
        val strategy = Strategy.create(StrategyId(1), MemberId(2), "strategy", Instant.EPOCH)
        val version = StrategyVersion.create(
            StrategyVersionId(3), strategy.id, Instant.EPOCH, AssetSymbol.QQQ,
            listOf(Condition(ComparisonOperator.GT, operandA = MetricReference(AssetSymbol.QQQ, MetricType.SIMPLE),
                operandB = LiteralValue(0.0))),
            AssetSymbol.QQQ, SignalSessions(0), TimeBasedExit(1),
        )
        strategy.addVersion(version)

        assertFailsWith<IllegalArgumentException> { strategy.addVersion(version) }
        assertEquals(listOf(version), strategy.versions)
    }

    @Test
    fun `rejects a version owned by another strategy`() {
        val strategy = Strategy.create(
            id = StrategyId(1L),
            memberId = MemberId(2L),
            name = "strategy",
            createdAt = Instant.parse("2026-08-11T00:00:00Z"),
        )
        val otherStrategyVersion = StrategyVersion.create(
            id = StrategyVersionId(3L),
            strategyId = StrategyId(4L),
            createdAt = Instant.parse("2026-08-11T00:00:00Z"),
            primarySignalAsset = AssetSymbol.QQQ,
            conditions = listOf(
                Condition(
                    operator = ComparisonOperator.GT,
                    operandA = MetricReference(AssetSymbol.QQQ, MetricType.SIMPLE),
                    operandB = LiteralValue(0.0),
                ),
            ),
            executionAsset = AssetSymbol.QQQ,
            lag = SignalSessions(0),
            exit = TimeBasedExit(1),
        )

        assertFailsWith<IllegalArgumentException> { strategy.addVersion(otherStrategyVersion) }
    }
}
