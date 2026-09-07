package com.refinvest.core.strategy.domain

import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.strategy.domain.valueobject.LogicalCombinator
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

class StrategyVersionTest {
    private val strategyId = StrategyId(1L)

    @Test
    fun `rejects an asset outside the MVP universe`() {
        assertFailsWith<IllegalArgumentException> { AssetSymbol.from("AAPL") }
    }

    @Test
    fun `rejects empty conditions`() {
        assertFailsWith<IllegalArgumentException> {
            version(conditions = emptyList())
        }
    }

    @Test
    fun `rejects VIX as an asset outside the MVP universe`() {
        assertFailsWith<IllegalArgumentException> { AssetSymbol.from("VIX") }
    }

    @Test
    fun `accepts every MVP asset`() {
        assertEquals(
            setOf("QQQ", "SPY", "TQQQ", "SOXL", "BTCUSDT"),
            AssetSymbol.entries.map(AssetSymbol::name).toSet(),
        )
    }

    @Test
    fun `rejects a non-positive metric window`() {
        assertFailsWith<IllegalArgumentException> {
            MetricReference(AssetSymbol.QQQ, MetricType.RETURN, 0)
        }
    }

    @Test
    fun `rejects a window on a simple metric`() {
        assertFailsWith<IllegalArgumentException> {
            MetricReference(AssetSymbol.QQQ, MetricType.SIMPLE, 1)
        }
    }

    @Test
    fun `rejects a negative lag`() {
        assertFailsWith<IllegalArgumentException> { SignalSessions(-1) }
    }

    @Test
    fun `rejects a non-positive holding period`() {
        assertFailsWith<IllegalArgumentException> { TimeBasedExit(0) }
    }

    @Test
    fun `rejects logical combinator on the first condition`() {
        assertFailsWith<IllegalArgumentException> {
            version(conditions = listOf(condition(LogicalCombinator.AND)))
        }
    }

    @Test
    fun `rejects a missing logical combinator after the first condition`() {
        assertFailsWith<IllegalArgumentException> {
            version(conditions = listOf(condition(), condition()))
        }
    }

    @Test
    fun `copies conditions so a created version stays immutable`() {
        val conditions = mutableListOf(condition())
        val version = version(conditions = conditions)

        conditions += condition(LogicalCombinator.AND)

        assertEquals(1, version.conditions.size)
    }

    private fun version(
        conditions: List<Condition> = listOf(condition()),
        executionAsset: AssetSymbol = AssetSymbol.QQQ,
    ): StrategyVersion = StrategyVersion.create(
        id = StrategyVersionId(2L),
        strategyId = strategyId,
        createdAt = Instant.parse("2026-08-11T00:00:00Z"),
        primarySignalAsset = AssetSymbol.QQQ,
        conditions = conditions,
        executionAsset = executionAsset,
        lag = SignalSessions(0),
        exit = TimeBasedExit(1),
    )

    private fun condition(logicalCombinator: LogicalCombinator? = null): Condition = Condition(
        operator = ComparisonOperator.GT,
        logicalCombinator = logicalCombinator,
        operandA = MetricReference(AssetSymbol.QQQ, MetricType.SIMPLE),
        operandB = LiteralValue(0.0),
    )
}
