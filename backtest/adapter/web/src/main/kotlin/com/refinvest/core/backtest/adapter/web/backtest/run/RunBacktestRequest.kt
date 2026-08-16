package com.refinvest.core.backtest.adapter.web.backtest.run

import com.refinvest.core.backtest.domain.FeeModel
import com.refinvest.core.backtest.domain.Percent
import com.refinvest.core.backtest.domain.Period
import com.refinvest.core.backtest.domain.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate

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

data class PeriodRequest(
    @field:NotNull
    val start: LocalDate,
    @field:NotNull
    val end: LocalDate,
)

data class FeeModelRequest(
    @field:NotNull
    @field:DecimalMin("0.0")
    val commission: BigDecimal,
    @field:NotNull
    @field:DecimalMin("0.0")
    val slippage: BigDecimal,
)
