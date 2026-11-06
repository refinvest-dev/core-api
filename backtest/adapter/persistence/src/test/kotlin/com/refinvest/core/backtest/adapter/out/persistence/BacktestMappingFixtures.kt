package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.backtest.*
import com.refinvest.core.backtest.domain.valueobject.*
import com.refinvest.core.backtest.domain.valueobject.Period
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

internal object BacktestMappingFixtures {
    val time: Instant = Instant.parse("2026-10-29T00:00:00.123456789Z")
    val period = Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-03"))
    val snapshot = DatasetSnapshotId("snapshot-1")
    val engine = EngineVersion("engine-1")
    fun pending(id: Long = 1): BacktestRun = BacktestRun.createPending(BacktestRunId(id), StrategyId(11), StrategyVersionId(12),
        period, FeeModel(Percent(BigDecimal("0.0005")), Percent(BigDecimal("0.001"))), time)
    fun running(id: Long = 1): BacktestRun = pending(id).apply { start(period, snapshot, engine) }
    fun completed(id: Long = 1, zero: Boolean = false): BacktestRun = running(id).apply { complete(result(id, zero = zero)) }
    fun result(id: Long = 1, snapshot: DatasetSnapshotId = this.snapshot, zero: Boolean = false, amount: String = "0.10"): BacktestResult {
        val curve = listOf(EquityCurvePoint(period.start, BigDecimal.ONE), EquityCurvePoint(period.end, BigDecimal("1.1")))
        val trade = Trade(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T01:00:00Z"), BigDecimal("100.12345678"),
            Instant.parse("2025-01-02T01:00:00Z"), BigDecimal("110.12345678"), BigDecimal(amount), 1)
        return BacktestResult(
            BacktestRunId(id),
            if (zero) BacktestResultMetrics(null, null, null, null, null, 0, null, null, null)
            else BacktestResultMetrics(BigDecimal(amount), null, BigDecimal("-0.03"), null, BigDecimal.ONE, 1, BigDecimal(amount), BigDecimal.ONE, null),
            if (zero) emptyList() else curve, if (zero) emptyList() else listOf(trade),
            Benchmark(BuyAndHoldResult("TQQQ", curve, BigDecimal("0.10"), null, BigDecimal("-0.02")),
                if (zero) null else BuyAndHoldResult("BTCUSDT", curve, BigDecimal("0.10"), BigDecimal("0.08"), BigDecimal("-0.01"))),
            SignalExecutionMarketRelation.CROSS_MARKET,
            if (zero) SignalExecutionDelay(BigDecimal.ZERO, BigDecimal.ZERO, emptyList()) else SignalExecutionDelay(BigDecimal.ONE, BigDecimal.ONE, listOf(BigDecimal.ONE)),
            if (zero) SampleSizeWarning.ZERO else SampleSizeWarning.LOW,
            DataIntegrityStatus(snapshot, true, true))
    }
}
