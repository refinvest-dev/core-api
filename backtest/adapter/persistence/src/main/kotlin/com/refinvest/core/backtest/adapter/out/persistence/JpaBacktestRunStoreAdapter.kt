package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import org.springframework.stereotype.Repository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Repository
class JpaBacktestRunStoreAdapter(
    private val backtestRunJpaStore: BacktestRunJpaStore,
    private val backtestResultJpaStore: BacktestResultJpaStore,
    private val resultPayloadMapper: BacktestResultPayloadMapper,
    private val meterRegistry: MeterRegistry,
) : BacktestRunStore {
    override fun save(backtestRun: BacktestRun) {
        val previousStatus = backtestRunJpaStore.findById(backtestRun.id.value).orElse(null)?.status
        val terminal = backtestRun.status in setOf(BacktestRunStatus.COMPLETED, BacktestRunStatus.FAILED)
        try {
            backtestRunJpaStore.save(backtestRun.toEntity())
            backtestRun.result?.let { result ->
                backtestResultJpaStore.save(
                    BacktestResultJpaEntity(
                        backtestRunId = backtestRun.id.value,
                        resultPayload = resultPayloadMapper.serialize(result),
                    ),
                )
            }
        } catch (exception: Exception) {
            if (terminal) {
                meterRegistry.counter("refinvest.core.backtest.terminal.persistence.failures").increment()
                logger.warn("event=backtest_terminal_persistence_failed runId={}", backtestRun.id.value)
            }
            throw exception
        }
        if (terminal &&
            previousStatus !in setOf(BacktestRunStatusJpa.COMPLETED, BacktestRunStatusJpa.FAILED)
        ) {
            val observeSuccess = {
                meterRegistry.counter("refinvest.backtest.terminal", "status", backtestRun.status.name).increment()
                logger.info("event=backtest_terminal_persisted runId={} status={}",
                    backtestRun.id.value, backtestRun.status.name)
            }
            val observeFailure = {
                meterRegistry.counter("refinvest.core.backtest.terminal.persistence.failures").increment()
                logger.warn("event=backtest_terminal_persistence_failed runId={}", backtestRun.id.value)
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        if (status == TransactionSynchronization.STATUS_COMMITTED) observeSuccess() else observeFailure()
                    }
                })
            } else {
                observeSuccess()
            }
        }
    }

    override fun findById(id: BacktestRunId): BacktestRun? =
        backtestRunJpaStore.findById(id.value).orElse(null)?.toDomain()

    private fun BacktestRun.toEntity(): BacktestRunJpaEntity {
        return BacktestRunJpaEntity(
            id = id.value,
            strategyId = strategyId.value,
            strategyVersionId = strategyVersionId.value,
            requestedPeriodStart = requestedPeriod.start,
            requestedPeriodEnd = requestedPeriod.end,
            commission = feeModel.commission.value,
            slippage = feeModel.slippage.value,
            status = BacktestRunStatusJpa.valueOf(status.name),
            actualPeriodStart = actualPeriod?.start,
            actualPeriodEnd = actualPeriod?.end,
            datasetSnapshotId = datasetSnapshotId?.value,
            engineVersion = engineVersion?.value,
            failureReason = failureReason,
            errorCode = errorCode,
            createdAt = createdAt,
        )
    }

    private fun BacktestRunJpaEntity.toDomain(): BacktestRun {
        val runId = BacktestRunId(id)
        val result = backtestResultJpaStore.findById(runId.value)
            .map { entity -> resultPayloadMapper.deserialize(entity.resultPayload) }
            .orElse(null)
        return BacktestRun.restore(
            id = runId,
            strategyId = StrategyId(strategyId),
            strategyVersionId = StrategyVersionId(strategyVersionId),
            requestedPeriod = Period(requestedPeriodStart, requestedPeriodEnd),
            feeModel = FeeModel(Percent(commission), Percent(slippage)),
            createdAt = createdAt,
            status = BacktestRunStatus.valueOf(status.name),
            actualPeriod = actualPeriodStart?.let { start -> Period(start, requireNotNull(actualPeriodEnd)) },
            datasetSnapshotId = datasetSnapshotId?.let(::DatasetSnapshotId),
            engineVersion = engineVersion?.let(::EngineVersion),
            result = result,
            failureReason = failureReason,
            errorCode = errorCode,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(JpaBacktestRunStoreAdapter::class.java)
    }
}
