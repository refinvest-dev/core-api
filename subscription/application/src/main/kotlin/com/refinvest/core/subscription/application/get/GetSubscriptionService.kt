package com.refinvest.core.subscription.application.get

import com.refinvest.core.subscription.port.inbound.get.GetSubscriptionQuery
import com.refinvest.core.subscription.port.inbound.get.GetSubscriptionResult
import com.refinvest.core.subscription.port.inbound.get.GetSubscriptionUseCase
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionReader
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
