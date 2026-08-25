package com.refinvest.core.subscription.port.inbound.create

fun interface CreateSubscriptionUseCase {
    fun execute(command: CreateSubscriptionCommand): CreateSubscriptionResult
}
