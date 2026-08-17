package com.refinvest.core.backtest.port.inbound.backtest.run

import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId

data class RunBacktestCommand(
    val strategyVersionId: StrategyVersionId,
    val period: Period,
    val feeModel: FeeModel,
)
