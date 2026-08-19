package com.refinvest.core.backtest.application.backtest.poll

import com.refinvest.core.backtest.port.inbound.backtest.poll.PollBacktestStatusQuery
import com.refinvest.core.backtest.port.inbound.backtest.poll.PollBacktestStatusResult
import com.refinvest.core.backtest.port.inbound.backtest.poll.PollBacktestStatusUseCase
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import org.springframework.stereotype.Service

@Service
class PollBacktestStatusService(
    private val backtestRunReader: BacktestRunReader,
) : PollBacktestStatusUseCase {
    override fun execute(query: PollBacktestStatusQuery): PollBacktestStatusResult? =
        backtestRunReader.findById(query.backtestRunId)?.let { run ->
            PollBacktestStatusResult(
                id = run.id,
                strategyId = run.strategyId,
                strategyVersionId = run.strategyVersionId,
                requestedPeriod = run.requestedPeriod,
                feeModel = run.feeModel,
                status = run.status,
                createdAt = run.createdAt,
            )
        }
}
