package com.refinvest.core.backtest.adapter.web.backtest.list

import com.refinvest.core.backtest.port.inbound.list.BacktestRunSummary

data class LatestCompletedBacktestRunResponse(
    val run: BacktestRunSummaryResponse?,
) {
    companion object {
        fun from(summary: BacktestRunSummary?): LatestCompletedBacktestRunResponse =
            LatestCompletedBacktestRunResponse(summary?.let(BacktestRunSummaryResponse::from))
    }
}
