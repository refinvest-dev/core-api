package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.adapter.web.backtest.run.FeeModelResponse
import com.refinvest.core.backtest.adapter.web.backtest.run.PeriodResponse
import com.refinvest.core.backtest.port.inbound.get.GetBacktestResultResult
import java.time.Instant

data class GetBacktestResultResponse(
    val id: String,
    val strategyId: String,
    val strategyVersionId: String,
    val status: String,
    val requestedPeriod: PeriodResponse,
    val feeModel: FeeModelResponse,
    val actualPeriod: PeriodResponse?,
    val datasetSnapshotId: String?,
    val engineVersion: String?,
    val result: BacktestResultResponse?,
    val failureReason: String?,
    val errorCode: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(result: GetBacktestResultResult): GetBacktestResultResponse = GetBacktestResultResponse(
            id = result.id.value.toString(),
            strategyId = result.strategyId.value.toString(),
            strategyVersionId = result.strategyVersionId.value.toString(),
            status = result.status.name,
            requestedPeriod = PeriodResponse(result.requestedPeriod.start, result.requestedPeriod.end),
            feeModel = FeeModelResponse(result.feeModel.commission.value, result.feeModel.slippage.value),
            actualPeriod = result.actualPeriod?.let { PeriodResponse(it.start, it.end) },
            datasetSnapshotId = result.datasetSnapshotId?.value,
            engineVersion = result.engineVersion?.value,
            result = result.result?.let(BacktestResultResponse::from),
            failureReason = result.failureReason,
            errorCode = result.errorCode,
            createdAt = result.createdAt,
        )
    }
}
