package com.refinvest.core.backtest.application.poll

import com.refinvest.core.backtest.port.inbound.poll.PollBacktestStatusQuery
import com.refinvest.core.backtest.port.inbound.poll.PollBacktestStatusResult
import com.refinvest.core.backtest.port.inbound.poll.PollBacktestStatusUseCase
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
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
