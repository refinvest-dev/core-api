package com.refinvest.core.backtest.application.execution

import com.refinvest.core.backtest.port.inbound.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunWithoutExecutionCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaStore
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class RecordBacktestRunExecutionService(
    private val backtestRunStore: BacktestRunStore,
    private val backtestQuotaStore: BacktestQuotaStore,
) : RecordBacktestRunExecutionUseCase {
    @Transactional
    override fun execute(command: RecordBacktestRunExecutionCommand): BacktestRunId {
        val backtestRun = requireNotNull(backtestRunStore.findById(command.backtestRunId)) {
            "Backtest run not found"
        }

        when (command) {
            is StartBacktestRunCommand -> backtestRun.start(
                actualPeriod = command.actualPeriod,
                datasetSnapshotId = command.datasetSnapshotId,
                engineVersion = command.engineVersion,
            )
            is CompleteBacktestRunCommand -> backtestRun.complete(command.result)
            is FailBacktestRunCommand -> backtestRun.fail(command.failureReason, command.errorCode)
            is FailBacktestRunWithoutExecutionCommand -> backtestRun.failWithoutExecution(command.failureReason, command.errorCode)
        }
        backtestRunStore.save(backtestRun)

        if (command is CompleteBacktestRunCommand ||
            command is FailBacktestRunCommand ||
            command is FailBacktestRunWithoutExecutionCommand
        ) {
            backtestQuotaStore.releaseConcurrentCapacity(backtestRun.id)
        }

        return backtestRun.id
    }
}
