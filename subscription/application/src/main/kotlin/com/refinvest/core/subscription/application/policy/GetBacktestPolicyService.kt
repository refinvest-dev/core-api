package com.refinvest.core.subscription.application.policy

import com.refinvest.core.subscription.domain.policy.BacktestPolicy
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.policy.GetBacktestPolicyQuery
import com.refinvest.core.subscription.port.inbound.policy.GetBacktestPolicyResult
import com.refinvest.core.subscription.port.inbound.policy.GetBacktestPolicyUseCase
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetBacktestPolicyService(
    private val subscriptionReader: SubscriptionReader,
) : GetBacktestPolicyUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetBacktestPolicyQuery): GetBacktestPolicyResult {
        val tier = subscriptionReader.findByMemberId(query.memberId)?.tier ?: SubscriptionTier.FREE
        val policy = BacktestPolicy.forTier(tier)
        return GetBacktestPolicyResult(
            monthlyExecutionLimit = policy.monthlyExecutionLimit,
            maxConcurrentRuns = policy.maxConcurrentRuns,
            maxRequestedPeriodDays = policy.maxRequestedPeriodDays,
            allowedAssets = policy.allowedAssets.toSet(),
        )
    }
}
