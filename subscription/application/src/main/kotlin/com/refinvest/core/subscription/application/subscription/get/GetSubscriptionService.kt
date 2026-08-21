package com.refinvest.core.subscription.application.subscription.get

import com.refinvest.core.subscription.port.inbound.subscription.get.GetSubscriptionQuery
import com.refinvest.core.subscription.port.inbound.subscription.get.GetSubscriptionResult
import com.refinvest.core.subscription.port.inbound.subscription.get.GetSubscriptionUseCase
import com.refinvest.core.subscription.port.outbound.SubscriptionReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetSubscriptionService(
    private val subscriptionReader: SubscriptionReader,
) : GetSubscriptionUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetSubscriptionQuery): GetSubscriptionResult? = subscriptionReader
        .findByMemberId(query.memberId)
        ?.let { subscription -> GetSubscriptionResult(subscription.id, subscription.tier) }
}
