package com.refinvest.core.backtest.adapter.web.backtest.poll

import com.refinvest.core.backtest.adapter.web.backtest.run.FeeModelResponse
import com.refinvest.core.backtest.adapter.web.backtest.run.PeriodResponse
import com.refinvest.core.backtest.port.inbound.poll.PollBacktestStatusResult
import java.time.Instant

data class PollBacktestStatusResponse(
    val id: String,
    val strategyId: String,
    val strategyVersionId: String,
    val status: String,
    val requestedPeriod: PeriodResponse,
    val feeModel: FeeModelResponse,
    val createdAt: Instant,
) {
    companion object {
        fun from(result: PollBacktestStatusResult): PollBacktestStatusResponse = PollBacktestStatusResponse(
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
