package com.refinvest.core.auth.adapter.web.auth.subscription

import com.refinvest.core.subscription.port.inbound.subscription.upgrade.UpgradeSubscriptionResult

data class UpgradeSubscriptionResponse(
    val tier: String,
) {
    companion object {
        fun from(result: UpgradeSubscriptionResult): UpgradeSubscriptionResponse = UpgradeSubscriptionResponse(
            tier = result.tier.name,
        )
    }
}
