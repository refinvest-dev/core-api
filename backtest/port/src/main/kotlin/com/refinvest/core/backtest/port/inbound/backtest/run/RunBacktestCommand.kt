package com.refinvest.core.backtest.port.inbound.backtest.run

import com.refinvest.core.backtest.domain.FeeModel
import com.refinvest.core.backtest.domain.Period
import com.refinvest.core.backtest.domain.StrategyVersionId

data class RunBacktestCommand(
    val strategyVersionId: StrategyVersionId,
    val period: Period,
    val feeModel: FeeModel,
)
