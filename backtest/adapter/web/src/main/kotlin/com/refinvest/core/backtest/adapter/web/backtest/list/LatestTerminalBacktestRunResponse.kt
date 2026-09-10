package com.refinvest.core.backtest.adapter.web.backtest.list

import com.refinvest.core.backtest.port.inbound.list.BacktestRunSummary

data class LatestTerminalBacktestRunResponse(
    val run: BacktestRunSummaryResponse?,
) {
    companion object {
        fun from(summary: BacktestRunSummary?): LatestTerminalBacktestRunResponse =
            LatestTerminalBacktestRunResponse(summary?.let(BacktestRunSummaryResponse::from))
    }
}
