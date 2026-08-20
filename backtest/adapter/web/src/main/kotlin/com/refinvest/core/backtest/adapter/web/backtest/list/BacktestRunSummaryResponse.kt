package com.refinvest.core.backtest.adapter.web.backtest.list

import com.refinvest.core.backtest.adapter.web.backtest.run.FeeModelResponse
import com.refinvest.core.backtest.adapter.web.backtest.run.PeriodResponse
import com.refinvest.core.backtest.port.inbound.backtest.list.BacktestRunSummary
import java.time.Instant

data class BacktestRunSummaryResponse(
    val id: String,
    val strategyId: String,
    val strategyVersionId: String,
    val status: String,
    val requestedPeriod: PeriodResponse,
    val feeModel: FeeModelResponse,
    val createdAt: Instant,
) {
    companion object {
        fun from(summary: BacktestRunSummary): BacktestRunSummaryResponse = BacktestRunSummaryResponse(
            id = summary.id.value.toString(),
            strategyId = summary.strategyId.value.toString(),
            strategyVersionId = summary.strategyVersionId.value.toString(),
            status = summary.status.name,
            requestedPeriod = PeriodResponse(summary.requestedPeriod.start, summary.requestedPeriod.end),
            feeModel = FeeModelResponse(summary.feeModel.commission.value, summary.feeModel.slippage.value),
            createdAt = summary.createdAt,
        )
    }
}
