package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.BacktestResult

data class BacktestResultResponse(
    val backtestRunId: String,
    val metrics: BacktestResultMetricsResponse,
    val equityCurve: List<EquityCurvePointResponse>,
    val trades: List<TradeResponse>,
    val benchmark: BenchmarkResponse,
    val signalExecutionMarketRelation: String,
    val signalExecutionDelay: SignalExecutionDelayResponse,
    val sampleSizeWarning: String,
    val dataIntegrityStatus: DataIntegrityStatusResponse,
) {
    companion object {
        fun from(result: BacktestResult): BacktestResultResponse = BacktestResultResponse(
            backtestRunId = result.backtestRunId.value.toString(),
            metrics = BacktestResultMetricsResponse.from(result.metrics),
            equityCurve = result.equityCurve.map(EquityCurvePointResponse::from),
            trades = result.trades.map(TradeResponse::from),
            benchmark = BenchmarkResponse.from(result.benchmark),
            signalExecutionMarketRelation = result.signalExecutionMarketRelation.name,
            signalExecutionDelay = SignalExecutionDelayResponse.from(result.signalExecutionDelay),
            sampleSizeWarning = result.sampleSizeWarning.name,
            dataIntegrityStatus = DataIntegrityStatusResponse.from(result.dataIntegrityStatus),
        )
    }
}
