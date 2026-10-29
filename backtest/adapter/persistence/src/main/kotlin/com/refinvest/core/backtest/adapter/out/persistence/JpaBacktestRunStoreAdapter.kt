package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

@Repository
class JpaBacktestRunStoreAdapter(
    private val backtestRunJpaStore: BacktestRunJpaStore,
    private val backtestResultJpaStore: BacktestResultJpaStore,
    private val resultPayloadMapper: BacktestResultPayloadMapper,
    private val meterRegistry: MeterRegistry,
    private val entityManager: EntityManager,
) : BacktestRunStore {
    @Transactional
    override fun save(backtestRun: BacktestRun) {
        val existing = loadLocked(backtestRun.id)
        val previousStatus = existing?.status
        val terminal = backtestRun.status in setOf(BacktestRunStatus.COMPLETED, BacktestRunStatus.FAILED)
        try {
            val mapped = BacktestRunPersistenceMapper.toPersistence(backtestRun, resultPayloadMapper)
            val existingResult = backtestResultJpaStore.findById(backtestRun.id.value).orElse(null)
            if (existing != null) {
                assertCompatible(existing, mapped, existingResult)
                if (previousStatus in TERMINAL_STATUSES) return
                backtestRunJpaStore.save(mapped.run)
            } else {
                require(existingResult == null) { "Cannot attach an existing result to a new BacktestRun" }
                entityManager.persist(mapped.run)
            }
            mapped.result?.let(entityManager::persist)
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

    @Transactional(propagation = Propagation.MANDATORY)
    override fun findById(id: BacktestRunId): BacktestRun? {
        val entity = loadLocked(id) ?: return null
        val result = backtestResultJpaStore.findById(id.value).orElse(null)
        return BacktestRunPersistenceMapper.toDomain(entity, result, resultPayloadMapper)
    }

    private fun loadLocked(id: BacktestRunId): BacktestRunJpaEntity? {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Backtest command access requires a transaction" }
        backtestRunJpaStore.lockById(id.value) ?: return null
        return backtestRunJpaStore.findById(id.value).orElse(null)?.also {
            // A previous read may be cached; reload after acquiring the root lock.
            entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE)
        }
    }

    private fun assertCompatible(stored: BacktestRunJpaEntity, next: BacktestRunPersistenceState,
                                 storedResult: BacktestResultJpaEntity?) {
        val candidate = next.run
        require(stored.strategyId == candidate.strategyId && stored.strategyVersionId == candidate.strategyVersionId &&
            stored.requestedPeriodStart == candidate.requestedPeriodStart && stored.requestedPeriodEnd == candidate.requestedPeriodEnd &&
            stored.commission.atStoredScale().compareTo(candidate.commission.atStoredScale()) == 0 &&
            stored.slippage.atStoredScale().compareTo(candidate.slippage.atStoredScale()) == 0 &&
            stored.createdAt.atStoredPrecision() == candidate.createdAt.atStoredPrecision()) {
            "Existing BacktestRun inputs cannot be replaced"
        }
        require(stored.status == BacktestRunStatusJpa.PENDING ||
            (stored.status == BacktestRunStatusJpa.RUNNING && candidate.status != BacktestRunStatusJpa.PENDING) ||
            stored.status == candidate.status) { "BacktestRun state cannot move backwards or replace a terminal outcome" }
        if (stored.status in TERMINAL_STATUSES || stored.actualPeriodStart != null || stored.actualPeriodEnd != null || stored.datasetSnapshotId != null || stored.engineVersion != null) {
            require(stored.actualPeriodStart == candidate.actualPeriodStart && stored.actualPeriodEnd == candidate.actualPeriodEnd &&
                stored.datasetSnapshotId == candidate.datasetSnapshotId && stored.engineVersion == candidate.engineVersion) {
                "Pinned Compute execution metadata cannot be replaced"
            }
        }
        if (stored.status in TERMINAL_STATUSES) {
            require(stored.failureReason == candidate.failureReason && stored.errorCode == candidate.errorCode) {
                "Terminal failure details cannot be replaced"
            }
            if (stored.status == BacktestRunStatusJpa.COMPLETED) {
                require(storedResult != null && next.result != null &&
                    resultPayloadMapper.equivalent(storedResult.resultPayload, next.result.resultPayload)) { "Completed result cannot be replaced" }
            } else require(storedResult == null && next.result == null) { "FAILED BacktestRun must not have a result" }
        } else require(storedResult == null) { "Nonterminal BacktestRun must not have a result" }
    }

    private fun Instant.atStoredPrecision(): Instant = plusNanos(500).truncatedTo(ChronoUnit.MICROS)
    private fun BigDecimal.atStoredScale(): BigDecimal = setScale(8, RoundingMode.HALF_UP)

    private companion object {
        val TERMINAL_STATUSES = setOf(BacktestRunStatusJpa.COMPLETED, BacktestRunStatusJpa.FAILED)
        val logger = LoggerFactory.getLogger(JpaBacktestRunStoreAdapter::class.java)
    }
}
