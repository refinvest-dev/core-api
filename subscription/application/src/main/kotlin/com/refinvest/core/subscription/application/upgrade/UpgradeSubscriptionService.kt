package com.refinvest.core.subscription.application.upgrade

import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.port.inbound.upgrade.UpgradeSubscriptionCommand
import com.refinvest.core.subscription.port.inbound.upgrade.UpgradeSubscriptionResult
import com.refinvest.core.subscription.port.inbound.upgrade.UpgradeSubscriptionUseCase
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class UpgradeSubscriptionService(
    private val subscriptionStore: SubscriptionStore,
) : UpgradeSubscriptionUseCase {
    @Transactional
    override fun execute(command: UpgradeSubscriptionCommand): UpgradeSubscriptionResult {
        val subscription = subscriptionStore.findByMemberId(command.memberId)
            ?: Subscription.createDefault(command.memberId)
        val upgradedSubscription = subscription.upgrade()
        subscriptionStore.save(upgradedSubscription)
        return UpgradeSubscriptionResult(upgradedSubscription.id, upgradedSubscription.tier)
    }
}
