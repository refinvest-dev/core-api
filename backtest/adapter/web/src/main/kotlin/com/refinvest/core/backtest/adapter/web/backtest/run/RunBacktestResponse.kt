package com.refinvest.core.backtest.adapter.web.backtest.run

import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestResult
import java.time.Instant

data class RunBacktestResponse(
    val id: String,
    val strategyId: String,
    val strategyVersionId: String,
    val status: String,
    val requestedPeriod: PeriodResponse,
    val feeModel: FeeModelResponse,
    val createdAt: Instant,
) {
    companion object {
        fun from(result: RunBacktestResult): RunBacktestResponse = RunBacktestResponse(
            id = result.id.value.toString(),
            strategyId = result.strategyId.value.toString(),
            strategyVersionId = result.strategyVersionId.value.toString(),
            status = result.status.name,
            requestedPeriod = PeriodResponse(result.requestedPeriod.start, result.requestedPeriod.end),
            feeModel = FeeModelResponse(result.feeModel.commission.value, result.feeModel.slippage.value),
            createdAt = result.createdAt,
        )
    }
}
