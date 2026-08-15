package com.refinvest.core.strategy.application.strategy.define

import com.refinvest.core.strategy.domain.AssetSymbol
import com.refinvest.core.strategy.domain.ComparisonOperator
import com.refinvest.core.strategy.domain.Condition
import com.refinvest.core.strategy.domain.LiteralValue
import com.refinvest.core.strategy.domain.MemberId
import com.refinvest.core.strategy.domain.MetricReference
import com.refinvest.core.strategy.domain.MetricType
import com.refinvest.core.strategy.domain.SignalSessions
import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.StrategyVersionId
import com.refinvest.core.strategy.domain.TimeBasedExit
import com.refinvest.core.strategy.port.inbound.strategy.define.DefineStrategyVersionCommand
import com.refinvest.core.strategy.port.outbound.StrategyStore
import com.refinvest.core.strategy.port.outbound.StrategyVersionIdGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefineStrategyVersionServiceTest {
    private val strategyId = StrategyId(1L)
    private val clock = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `adds an immutable version to the strategy and saves it`() {
        val strategy = strategy()
        val store = InMemoryStrategyStore(strategy)
        val service = DefineStrategyVersionService(
            strategyStore = store,
            strategyVersionIdGenerator = StrategyVersionIdGenerator { StrategyVersionId(2L) },
            clock = clock,
        )

        val result = service.execute(command())

        assertEquals(StrategyVersionId(2L), result?.id)
        assertEquals(1, store.saved?.versions?.size)
        assertEquals(AssetSymbol.QQQ, store.saved?.versions?.single()?.primarySignalAsset)
    }

    @Test
    fun `returns null when the strategy does not exist`() {
        val service = DefineStrategyVersionService(
            strategyStore = InMemoryStrategyStore(null),
            strategyVersionIdGenerator = StrategyVersionIdGenerator { StrategyVersionId(2L) },
            clock = clock,
        )

        assertNull(service.execute(command()))
    }

    private fun strategy(): Strategy = Strategy.create(
        id = strategyId,
        memberId = MemberId(1L),
        name = "volatility hypothesis",
        createdAt = clock.instant(),
    )

    private fun command(): DefineStrategyVersionCommand = DefineStrategyVersionCommand(
        strategyId = strategyId,
        primarySignalAsset = AssetSymbol.QQQ,
        conditions = listOf(
            Condition(
                operator = ComparisonOperator.LT,
                operandA = MetricReference(AssetSymbol.QQQ, MetricType.RETURN, 5),
                operandB = LiteralValue(-0.07),
            ),
        ),
        executionAsset = AssetSymbol.TQQQ,
        lag = SignalSessions(3),
        exit = TimeBasedExit(5),
    )

    private class InMemoryStrategyStore(
        private val stored: Strategy?,
    ) : StrategyStore {
        var saved: Strategy? = null

        override fun findById(id: StrategyId): Strategy? = stored?.takeIf { it.id == id }

        override fun save(strategy: Strategy) {
            saved = strategy
        }
    }
}
