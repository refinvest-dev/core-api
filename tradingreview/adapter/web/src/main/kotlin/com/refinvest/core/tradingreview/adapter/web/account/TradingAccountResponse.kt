package com.refinvest.core.tradingreview.adapter.web.account

import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult
import java.time.Instant

data class TradingAccountResponse(val id: String, val venue: TradingVenue, val displayName: String,
                                  val status: String, val createdAt: Instant) {
    companion object {
        fun from(account: TradingAccountResult) = TradingAccountResponse(account.id.value.toString(), account.venue,
            account.displayName, account.status.name, account.createdAt)
    }
}
