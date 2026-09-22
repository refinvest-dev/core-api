package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JpaBacktestRunStoreAdapterMetricsTest {
    @Test
    fun `counts first persisted terminal transition only once`() {
        val fixture = fixture()
        fixture.adapter.save(fixture.run)
        assertEquals(1.0, fixture.registry.get("refinvest.backtest.terminal")
            .tag("status", "FAILED").counter().count())

        fixture.previous.status = BacktestRunStatusJpa.FAILED
        fixture.adapter.save(fixture.run)
        assertEquals(1.0, fixture.registry.get("refinvest.backtest.terminal")
            .tag("status", "FAILED").counter().count())
        assertEquals(setOf("status"), fixture.registry.get("refinvest.backtest.terminal")
            .tag("status", "FAILED").counter().id.tags.map { it.key }.toSet())
    }

    @Test
    fun `counts terminal persistence failure without a successful terminal transition`() {
        val fixture = fixture()
        Mockito.`when`(fixture.store.save(Mockito.any(BacktestRunJpaEntity::class.java)))
            .thenThrow(IllegalStateException("database unavailable"))

        assertFailsWith<IllegalStateException> { fixture.adapter.save(fixture.run) }

        assertEquals(1.0, fixture.registry.get("refinvest.core.backtest.terminal.persistence.failures")
            .counter().count())
        assertEquals(null, fixture.registry.find("refinvest.backtest.terminal").counter())
    }

    private fun fixture(): Fixture {
        val run = BacktestRun.createPending(
            BacktestRunId(42L), StrategyId(7L), StrategyVersionId(11L),
            Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")),
            FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
            Instant.parse("2026-09-22T00:00:00Z"),
        )
        run.failWithoutExecution("unavailable")
        val previous = BacktestRunJpaEntity(
            id = 42L, strategyId = 7L, strategyVersionId = 11L,
            requestedPeriodStart = LocalDate.parse("2025-01-01"),
            requestedPeriodEnd = LocalDate.parse("2025-12-31"),
            commission = BigDecimal.ZERO, slippage = BigDecimal.ZERO,
            status = BacktestRunStatusJpa.PENDING,
            createdAt = Instant.parse("2026-09-22T00:00:00Z"),
        )
        val store = Mockito.mock(BacktestRunJpaStore::class.java)
        Mockito.`when`(store.findById(42L)).thenReturn(Optional.of(previous))
        val registry = SimpleMeterRegistry()
        val adapter = JpaBacktestRunStoreAdapter(
            store, Mockito.mock(BacktestResultJpaStore::class.java),
            Mockito.mock(BacktestResultPayloadMapper::class.java), registry,
        )
        return Fixture(adapter, store, run, previous, registry)
    }

    private data class Fixture(
        val adapter: JpaBacktestRunStoreAdapter,
        val store: BacktestRunJpaStore,
        val run: BacktestRun,
        val previous: BacktestRunJpaEntity,
        val registry: SimpleMeterRegistry,
    )
}
