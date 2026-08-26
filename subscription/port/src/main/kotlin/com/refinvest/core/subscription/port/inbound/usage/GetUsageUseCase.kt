package com.refinvest.core.subscription.port.inbound.usage

fun interface GetUsageUseCase {
    fun execute(query: GetUsageQuery): GetUsageResult
}
