package com.refinvest.core.subscription.port.inbound.upgrade

fun interface UpgradeSubscriptionUseCase {
    fun execute(command: UpgradeSubscriptionCommand): UpgradeSubscriptionResult
}
