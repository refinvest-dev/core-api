package com.refinvest.core.backtest.application.backtest.execution

import com.refinvest.core.backtest.port.inbound.backtest.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.FailBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.RecordBacktestRunExecutionCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.backtest.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.BacktestQuotaStore
import com.refinvest.core.backtest.port.outbound.BacktestRunStore
import com.refinvest.core.strategy.port.inbound.strategy.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.strategy.version.backtest.LookupStrategyVersionForBacktestUseCase
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
            if (command is CompleteBacktestRunCommand || command is FailBacktestRunCommand) {
                val version = requireNotNull(
                    lookupStrategyVersionForBacktestUseCase.execute(
                        LookupStrategyVersionForBacktestQuery(run.strategyVersionId.value),
                    ),
                ) { "Strategy version not found" }
                backtestQuotaStore.releaseConcurrentCapacity(
                    memberId = version.ownerMemberId,
                    quotaMonth = YearMonth.from(run.createdAt.atZone(ZoneOffset.UTC)),
                )
            }
        }.id
}
