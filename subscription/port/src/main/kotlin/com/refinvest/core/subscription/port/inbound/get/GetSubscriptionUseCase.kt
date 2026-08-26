package com.refinvest.core.subscription.port.inbound.get

fun interface GetSubscriptionUseCase {
    fun execute(query: GetSubscriptionQuery): GetSubscriptionResult?
}
