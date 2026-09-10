package com.refinvest.core.backtest.application.execution

import com.refinvest.core.backtest.port.inbound.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunWithoutExecutionCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaStore
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.time.ZoneOffset

@Service
open class RecordBacktestRunExecutionService(
    private val backtestRunStore: BacktestRunStore,
    private val backtestQuotaStore: BacktestQuotaStore,
    private val lookupStrategyVersionForBacktestUseCase: LookupStrategyVersionForBacktestUseCase,
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
            releaseConcurrentCapacity(backtestRun)
        }

        return backtestRun.id
    }

    private fun releaseConcurrentCapacity(backtestRun: BacktestRun) {
        val strategyVersion = requireNotNull(
            lookupStrategyVersionForBacktestUseCase.execute(
                LookupStrategyVersionForBacktestQuery(backtestRun.strategyVersionId.value),
            ),
        ) { "Strategy version not found" }
        backtestQuotaStore.releaseConcurrentCapacity(
            memberId = strategyVersion.ownerMemberId,
            quotaMonth = YearMonth.from(backtestRun.createdAt.atZone(ZoneOffset.UTC)),
        )
    }
}
