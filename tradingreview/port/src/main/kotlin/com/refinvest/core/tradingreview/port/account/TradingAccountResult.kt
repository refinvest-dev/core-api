package com.refinvest.core.tradingreview.port.account

import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import java.time.Instant

data class TradingAccountResult(val id: TradingAccountId, val venue: TradingVenue, val displayName: String,
                                val status: TradingLifecycleStatus, val createdAt: Instant) {
    companion object {
        fun fromDomain(value: TradingAccount) = TradingAccountResult(value.id, value.venue, value.displayName, value.status, value.createdAt)
    }
}
