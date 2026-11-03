package com.refinvest.core.tradingreview.port.book

import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import java.time.Instant

data class TradingBookResult(val id: TradingBookId, val accountId: TradingAccountId, val venue: TradingVenue,
    val displayName: String, val productFamily: TradingProductFamily, val positionMode: TradingPositionMode,
    val settlementAsset: TradingSettlementAsset, val reviewTimezone: String, val status: TradingLifecycleStatus,
    val createdAt: Instant) {
    companion object {
        fun fromDomain(value: TradingBook) = TradingBookResult(value.id, value.accountId, value.venue, value.displayName,
            value.productFamily, value.positionMode, value.settlementAsset, value.reviewTimezone, value.status, value.createdAt)
    }
}
