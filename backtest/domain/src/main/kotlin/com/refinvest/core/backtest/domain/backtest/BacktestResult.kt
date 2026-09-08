package com.refinvest.core.backtest.domain.backtest

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

class BacktestResult(
    val backtestRunId: BacktestRunId,
    val metrics: BacktestResultMetrics,
    equityCurve: List<EquityCurvePoint>,
    trades: List<Trade>,
    val benchmark: Benchmark,
    val signalExecutionMarketRelation: SignalExecutionMarketRelation,
    val signalExecutionDelay: SignalExecutionDelay,
    val sampleSizeWarning: SampleSizeWarning,
    val dataIntegrityStatus: DataIntegrityStatus,
) {
    val equityCurve: List<EquityCurvePoint> = equityCurve.toList()
    val trades: List<Trade> = trades.toList()
}
