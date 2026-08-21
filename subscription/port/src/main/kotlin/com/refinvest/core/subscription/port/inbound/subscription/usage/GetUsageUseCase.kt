package com.refinvest.core.subscription.port.inbound.subscription.usage

fun interface GetUsageUseCase {
    fun execute(query: GetUsageQuery): GetUsageResult
}
