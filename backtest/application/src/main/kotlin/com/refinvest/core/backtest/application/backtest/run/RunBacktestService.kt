package com.refinvest.core.backtest.application.backtest.run

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestCommand
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestResult
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestUseCase
import com.refinvest.core.backtest.port.outbound.BacktestRunIdGenerator
import com.refinvest.core.backtest.port.outbound.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.BacktestQuotaReservation
import com.refinvest.core.backtest.port.outbound.BacktestQuotaStore
import com.refinvest.core.backtest.port.outbound.BacktestRunStore
import com.refinvest.core.backtest.port.inbound.backtest.run.AssetNotAllowedForPlanException
import com.refinvest.core.backtest.port.inbound.backtest.run.BacktestConcurrencyLimitExceededException
import com.refinvest.core.backtest.port.inbound.backtest.run.BacktestMonthlyLimitExceededException
import com.refinvest.core.backtest.port.inbound.backtest.run.BacktestPeriodNotAllowedException
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import com.refinvest.core.subscription.domain.policy.BacktestPolicy
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.subscription.get.GetSubscriptionQuery
import com.refinvest.core.subscription.port.inbound.subscription.get.GetSubscriptionUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Service
open class RunBacktestService(
    private val backtestRunStore: BacktestRunStore,
    private val backtestRunIdGenerator: BacktestRunIdGenerator,
    private val lookupStrategyVersionForBacktestUseCase: LookupStrategyVersionForBacktestUseCase,
    private val getSubscriptionUseCase: GetSubscriptionUseCase,
    private val backtestMemberIdProvider: BacktestMemberIdProvider,
    private val backtestQuotaStore: BacktestQuotaStore,
    private val clock: Clock,
) : RunBacktestUseCase {
    @Transactional
    override fun execute(command: RunBacktestCommand): RunBacktestResult {
        val version = lookupStrategyVersionForBacktestUseCase.execute(
            LookupStrategyVersionForBacktestQuery(command.strategyVersionId.value),
        ) ?: throw NoSuchElementException("Strategy version not found")
        val memberId = backtestMemberIdProvider.currentMemberId()
        if (version.ownerMemberId != memberId) throw NoSuchElementException("Strategy version not found")
        val tier = getSubscriptionUseCase.execute(GetSubscriptionQuery(memberId))?.tier ?: SubscriptionTier.FREE
        val policy = BacktestPolicy.forTier(tier)
        if (!policy.allowsAll(version.assetSymbols)) throw AssetNotAllowedForPlanException()
        val requestedPeriodDays = ChronoUnit.DAYS.between(command.period.start, command.period.end) + 1
        if (requestedPeriodDays > policy.maxRequestedPeriodDays) throw BacktestPeriodNotAllowedException()
        when (backtestQuotaStore.reserve(
            memberId = memberId,
            quotaMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC)),
            monthlyExecutionLimit = policy.monthlyExecutionLimit,
            maxConcurrentRuns = policy.maxConcurrentRuns,
        )) {
            BacktestQuotaReservation.RESERVED -> Unit
            BacktestQuotaReservation.MONTHLY_LIMIT_EXCEEDED -> throw BacktestMonthlyLimitExceededException()
            BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED -> throw BacktestConcurrencyLimitExceededException()
        }
        val backtestRun = BacktestRun.createPending(
            id = backtestRunIdGenerator.next(),
            strategyId = StrategyId(version.strategyId),
            strategyVersionId = command.strategyVersionId,
            requestedPeriod = command.period,
            feeModel = command.feeModel,
            createdAt = clock.instant(),
        )
        backtestRunStore.save(backtestRun)
        return RunBacktestResult(
            id = backtestRun.id,
            strategyId = backtestRun.strategyId,
            strategyVersionId = backtestRun.strategyVersionId,
            requestedPeriod = backtestRun.requestedPeriod,
            feeModel = backtestRun.feeModel,
            status = backtestRun.status,
            createdAt = backtestRun.createdAt,
        )
    }
}
