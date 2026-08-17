package com.refinvest.core.backtest.adapter.web.backtest.run

import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestCommand
import jakarta.validation.Valid

data class RunBacktestRequest(
    @field:Valid
    val period: PeriodRequest,
    @field:Valid
    val feeModel: FeeModelRequest,
) {
    fun toCommand(strategyVersionId: StrategyVersionId): RunBacktestCommand = RunBacktestCommand(
        strategyVersionId = strategyVersionId,
        period = Period(period.start, period.end),
        feeModel = FeeModel(Percent(feeModel.commission), Percent(feeModel.slippage)),
    )
}
