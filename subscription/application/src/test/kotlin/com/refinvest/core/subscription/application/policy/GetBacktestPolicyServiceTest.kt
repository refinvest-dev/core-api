package com.refinvest.core.subscription.application.policy

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.policy.GetBacktestPolicyQuery
import kotlin.test.Test
import kotlin.test.assertEquals

class GetBacktestPolicyServiceTest {
    @Test
    fun absentSubscriptionAndSavedFreeSubscriptionUseTheSameDefaultEntitlements() {
        val query = GetBacktestPolicyQuery(MemberId(7))
        val absent = GetBacktestPolicyService { id -> assertEquals(query.memberId, id); null }.execute(query)
        val saved = GetBacktestPolicyService { Subscription.createDefault(it) }.execute(query)
        assertEquals(saved, absent)
        assertEquals(30, absent.monthlyExecutionLimit)
        assertEquals(1, absent.maxConcurrentRuns)
        assertEquals(365L, absent.maxRequestedPeriodDays)
        assertEquals(setOf("QQQ", "SPY", "BTCUSDT"), absent.allowedAssets)
    }

    @Test
    fun proRetainsFiniteLimitsAndAllFiveMvpAssets() {
        val policy = GetBacktestPolicyService { Subscription.restore(it, SubscriptionTier.PRO) }
            .execute(GetBacktestPolicyQuery(MemberId(7)))
        assertEquals(500, policy.monthlyExecutionLimit)
        assertEquals(3, policy.maxConcurrentRuns)
        assertEquals(3650L, policy.maxRequestedPeriodDays)
        assertEquals(setOf("QQQ", "SPY", "TQQQ", "SOXL", "BTCUSDT"), policy.allowedAssets)
    }

    @Test
    fun aConsumersResultMutationCannotChangeFutureEntitlementDecisions() {
        val service = GetBacktestPolicyService { null }
        val query = GetBacktestPolicyQuery(MemberId(7))
        val first = service.execute(query)
        (first.allowedAssets as MutableSet<String>).add("TQQQ")
        assertEquals(setOf("QQQ", "SPY", "BTCUSDT"), service.execute(query).allowedAssets)
    }
}
