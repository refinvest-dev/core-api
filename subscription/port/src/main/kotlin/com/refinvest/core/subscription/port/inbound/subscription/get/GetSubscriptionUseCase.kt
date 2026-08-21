package com.refinvest.core.subscription.port.inbound.subscription.get

fun interface GetSubscriptionUseCase {
    fun execute(query: GetSubscriptionQuery): GetSubscriptionResult?
}
