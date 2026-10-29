package com.refinvest.core.backtest.domain.backtest

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import java.util.Collections

class BacktestResult(
    val backtestRunId: BacktestRunId,
    val metrics: BacktestResultMetrics,
    equityCurve: List<EquityCurvePoint>,
    trades: List<Trade>,
    benchmark: Benchmark,
    val signalExecutionMarketRelation: SignalExecutionMarketRelation,
    val signalExecutionDelay: SignalExecutionDelay,
    val sampleSizeWarning: SampleSizeWarning,
    val dataIntegrityStatus: DataIntegrityStatus,
) {
    val equityCurve: List<EquityCurvePoint> = Collections.unmodifiableList(ArrayList(equityCurve))
    val trades: List<Trade> = Collections.unmodifiableList(ArrayList(trades))
    val benchmark: Benchmark = Benchmark(benchmark.primary.immutableCopy(), benchmark.secondaryReference?.immutableCopy())

    private fun BuyAndHoldResult.immutableCopy(): BuyAndHoldResult =
        copy(equityCurve = Collections.unmodifiableList(ArrayList(equityCurve)))
}
