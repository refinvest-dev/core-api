package com.refinvest.core.subscription.application.subscription.upgrade

import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.port.inbound.subscription.upgrade.UpgradeSubscriptionCommand
import com.refinvest.core.subscription.port.inbound.subscription.upgrade.UpgradeSubscriptionResult
import com.refinvest.core.subscription.port.inbound.subscription.upgrade.UpgradeSubscriptionUseCase
import com.refinvest.core.subscription.port.outbound.SubscriptionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class UpgradeSubscriptionService(
    private val subscriptionStore: SubscriptionStore,
) : UpgradeSubscriptionUseCase {
    @Transactional
    override fun execute(command: UpgradeSubscriptionCommand): UpgradeSubscriptionResult {
        val upgradedSubscription = (subscriptionStore.findByMemberId(command.memberId)
            ?: Subscription.createDefault(command.memberId))
            .upgrade()
        subscriptionStore.save(upgradedSubscription)
        return UpgradeSubscriptionResult(upgradedSubscription.id, upgradedSubscription.tier)
    }
}
