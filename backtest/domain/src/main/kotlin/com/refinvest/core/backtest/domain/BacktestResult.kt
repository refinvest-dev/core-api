package com.refinvest.core.backtest.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class BacktestResult(
    val backtestRunId: BacktestRunId,
    val metrics: BacktestResultMetrics,
    equityCurve: List<EquityCurvePoint>,
    trades: List<Trade>,
    val benchmark: Benchmark,
    val signalExecutionDelay: SignalExecutionDelay,
    val sampleSizeWarning: SampleSizeWarning,
    val dataIntegrityStatus: DataIntegrityStatus,
) {
    val equityCurve: List<EquityCurvePoint> = equityCurve.toList()
    val trades: List<Trade> = trades.toList()
}

data class BacktestResultMetrics(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
    val sharpe: BigDecimal,
    val winRate: BigDecimal,
    val tradeCount: Int,
    val avgTradeReturn: BigDecimal,
    val avgHoldingPeriod: BigDecimal,
    val profitFactor: BigDecimal,
)

data class EquityCurvePoint(
    val date: LocalDate,
    val value: BigDecimal,
)

data class Trade(
    val signalTime: Instant,
    val entryTime: Instant,
    val entryPrice: BigDecimal,
    val exitTime: Instant,
    val exitPrice: BigDecimal,
    val returnPct: BigDecimal,
    val holdingPeriod: Int,
)

data class Benchmark(
    val primary: BuyAndHoldResult,
    val secondaryReference: BuyAndHoldResult?,
)

data class BuyAndHoldResult(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
)

class SignalExecutionDelay(
    val median: BigDecimal,
    val max: BigDecimal,
    distribution: List<BigDecimal>,
) {
    val distribution: List<BigDecimal> = distribution.toList()
}

enum class SampleSizeWarning {
    NONE,
    LOW,
    ZERO,
}

data class DataIntegrityStatus(
    val datasetSnapshotId: DatasetSnapshotId,
    val corporateActionsApplied: Boolean,
    val pointInTimeValidationPassed: Boolean,
)
