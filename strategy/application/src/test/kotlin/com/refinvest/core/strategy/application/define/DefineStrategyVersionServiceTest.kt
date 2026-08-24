package com.refinvest.core.strategy.application.define

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.MetricReference
import com.refinvest.core.strategy.domain.valueobject.MetricType
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
import com.refinvest.core.strategy.port.inbound.define.DefineStrategyVersionCommand
import com.refinvest.core.strategy.port.outbound.persistence.StrategyStore
import com.refinvest.core.strategy.port.outbound.id.StrategyVersionIdGenerator
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
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
            memberIdProvider = MemberIdProvider { MemberId(1L) },
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
            memberIdProvider = MemberIdProvider { MemberId(1L) },
            clock = clock,
        )

        assertNull(service.execute(command()))
    }

    @Test
    fun `returns null and does not save when the strategy belongs to another member`() {
        val store = InMemoryStrategyStore(strategy(memberId = MemberId(2L)))
        val service = DefineStrategyVersionService(
            strategyStore = store,
            strategyVersionIdGenerator = StrategyVersionIdGenerator { StrategyVersionId(2L) },
            memberIdProvider = MemberIdProvider { MemberId(1L) },
            clock = clock,
        )

        assertNull(service.execute(command()))
        assertNull(store.saved)
    }

    private fun strategy(memberId: MemberId = MemberId(1L)): Strategy = Strategy.create(
        id = strategyId,
        memberId = memberId,
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
