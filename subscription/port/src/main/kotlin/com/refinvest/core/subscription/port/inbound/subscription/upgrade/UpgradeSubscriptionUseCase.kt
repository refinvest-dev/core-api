package com.refinvest.core.subscription.port.inbound.subscription.upgrade

fun interface UpgradeSubscriptionUseCase {
    fun execute(command: UpgradeSubscriptionCommand): UpgradeSubscriptionResult
}
