package com.refinvest.core.backtest.adapter.web.backtest.list

import com.refinvest.core.backtest.port.inbound.backtest.list.ListBacktestRunsResult

data class ListBacktestRunsResponse(
    val items: List<BacktestRunSummaryResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun from(result: ListBacktestRunsResult): ListBacktestRunsResponse = ListBacktestRunsResponse(
            items = result.items.map(BacktestRunSummaryResponse::from),
            page = result.page,
            size = result.size,
            total = result.total,
        )
    }
}
