package com.refinvest.core.backtest.application.dispatch

import com.refinvest.core.backtest.port.inbound.dispatch.DispatchPendingBacktestsUseCase
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestRequest
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestSubmission
import com.refinvest.core.backtest.port.outbound.compute.ComputeClient
import com.refinvest.core.backtest.port.outbound.compute.ComputeConditionPayload
import com.refinvest.core.backtest.port.outbound.compute.ComputeConditionOperandPayload
import com.refinvest.core.backtest.port.outbound.compute.LiteralOperandPayload
import com.refinvest.core.backtest.port.outbound.compute.MetricOperandPayload
import com.refinvest.core.backtest.port.outbound.compute.MetricReferencePayload
import com.refinvest.core.backtest.port.outbound.compute.PositionPolicyPayload
import com.refinvest.core.backtest.port.outbound.compute.StrategyVersionPayload
import com.refinvest.core.backtest.port.outbound.compute.TimeBasedExitPayload
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaStore
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.BacktestComputeDispatchStore
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyConditionOperandForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyMetricReferenceForBacktest
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyVersionForBacktest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneOffset

@Service
open class DispatchPendingBacktestsService(
    private val backtestComputeDispatchStore: BacktestComputeDispatchStore,
    private val backtestRunStore: BacktestRunStore,
    private val backtestQuotaStore: BacktestQuotaStore,
    private val lookupStrategyVersionForBacktestUseCase: LookupStrategyVersionForBacktestUseCase,
    private val computeClient: ComputeClient,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) : DispatchPendingBacktestsUseCase {
    override fun execute(): Boolean {
        val now = clock.instant()
        val dispatch = transactionTemplate.execute {
            backtestComputeDispatchStore.claimNext(now, DISPATCH_LEASE_DURATION)
        } ?: return false

        val backtestRun = backtestRunStore.findById(dispatch.backtestRunId) ?: return false
        val strategyVersion = lookupStrategyVersionForBacktestUseCase.execute(
            LookupStrategyVersionForBacktestQuery(backtestRun.strategyVersionId.value),
        ) ?: return false
        val definition = requireNotNull(strategyVersion.definition) {
            "Strategy version definition is required for Compute dispatch"
        }

        when (val submission = computeClient.requestBacktest(
            ComputeBacktestRequest(
                idempotencyKey = dispatch.idempotencyKey,
                strategyVersionId = backtestRun.strategyVersionId,
                strategyVersion = definition.toComputePayload(),
                feeModel = backtestRun.feeModel,
                period = backtestRun.requestedPeriod,
            ),
        )) {
            is ComputeBacktestSubmission.Accepted -> transactionTemplate.execute {
                backtestComputeDispatchStore.markAccepted(
                    backtestRun.id,
                    dispatch.claimToken,
                    submission.computeRunId,
                )
            }
            is ComputeBacktestSubmission.RetryLater -> transactionTemplate.execute {
                backtestComputeDispatchStore.scheduleRetry(
                    backtestRun.id,
                    dispatch.claimToken,
                    clock.instant().plus(submission.retryAfter),
                )
            }
            is ComputeBacktestSubmission.Rejected -> transactionTemplate.execute {
                if (backtestComputeDispatchStore.markRejected(backtestRun.id, dispatch.claimToken)) {
                    backtestRun.failWithoutExecution(submission.reason)
                    backtestRunStore.save(backtestRun)
                    backtestQuotaStore.releaseConcurrentCapacity(
                        strategyVersion.ownerMemberId,
                        YearMonth.from(backtestRun.createdAt.atZone(ZoneOffset.UTC)),
                    )
                }
            }
        }
        return true
    }

    private fun StrategyVersionForBacktest.toComputePayload(): StrategyVersionPayload = StrategyVersionPayload(
        primarySignalAsset = primarySignalAsset,
        conditions = conditions.map { condition ->
            ComputeConditionPayload(
                operator = condition.operator,
                logicalCombinator = condition.logicalCombinator,
                operandA = condition.operandA.toComputePayload(),
                operandB = condition.operandB.toComputePayload(),
            )
        },
        executionAsset = executionAsset,
        lag = lag,
        exit = TimeBasedExitPayload(holdingSignalSessions),
        positionPolicy = PositionPolicyPayload(longOnly = true, singlePosition = true, duplicateEntry = "IGNORE"),
    )

    private fun StrategyConditionOperandForBacktest.toComputePayload(): ComputeConditionOperandPayload = when (this) {
        is com.refinvest.core.strategy.port.inbound.version.backtest.StrategyLiteralOperandForBacktest -> LiteralOperandPayload(value)
        is com.refinvest.core.strategy.port.inbound.version.backtest.StrategyMetricOperandForBacktest -> MetricOperandPayload(value.toComputePayload())
    }

    private fun StrategyMetricReferenceForBacktest.toComputePayload(): MetricReferencePayload =
        MetricReferencePayload(asset, metric, window)

    private companion object {
        val DISPATCH_LEASE_DURATION: Duration = Duration.ofMinutes(1)
    }
}
