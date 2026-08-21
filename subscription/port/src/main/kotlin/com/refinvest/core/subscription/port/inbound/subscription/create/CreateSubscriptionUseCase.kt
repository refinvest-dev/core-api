package com.refinvest.core.subscription.port.inbound.subscription.create

fun interface CreateSubscriptionUseCase {
    fun execute(command: CreateSubscriptionCommand): CreateSubscriptionResult
}
