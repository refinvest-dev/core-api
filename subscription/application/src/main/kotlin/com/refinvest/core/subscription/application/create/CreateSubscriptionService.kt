package com.refinvest.core.subscription.application.create

import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionCommand
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionResult
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionUseCase
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class CreateSubscriptionService(
    private val subscriptionStore: SubscriptionStore,
) : CreateSubscriptionUseCase {
    @Transactional
    override fun execute(command: CreateSubscriptionCommand): CreateSubscriptionResult {
        val subscription = Subscription.createDefault(command.memberId)
        subscriptionStore.save(subscription)
        return CreateSubscriptionResult(subscription.id, subscription.tier)
    }
}
