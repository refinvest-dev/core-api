package com.refinvest.core.backtest.application.backtest.execution

import com.refinvest.core.backtest.port.inbound.backtest.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.FailBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.RecordBacktestRunExecutionCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.backtest.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.BacktestRunStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class RecordBacktestRunExecutionService(
    private val backtestRunStore: BacktestRunStore,
) : RecordBacktestRunExecutionUseCase {
    @Transactional
    override fun execute(command: RecordBacktestRunExecutionCommand) =
        requireNotNull(backtestRunStore.findById(command.backtestRunId)) {
            "Backtest run not found"
        }.also { run ->
            when (command) {
                is StartBacktestRunCommand -> run.start(
                    actualPeriod = command.actualPeriod,
                    datasetSnapshotId = command.datasetSnapshotId,
                    engineVersion = command.engineVersion,
                )
                is CompleteBacktestRunCommand -> run.complete(command.result)
                is FailBacktestRunCommand -> run.fail(command.failureReason)
            }
            backtestRunStore.save(run)
        }.id
}
