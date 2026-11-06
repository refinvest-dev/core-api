package com.refinvest.core.subscription.port.inbound.policy

fun interface GetBacktestPolicyUseCase {
    fun execute(query: GetBacktestPolicyQuery): GetBacktestPolicyResult
}
