package com.refinvest.core.tradingreview.adapter.web.book

import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult
import java.time.Instant

data class TradingBookResponse(val id: String, val tradingAccountId: String, val venue: TradingVenue,
                               val productFamily: TradingProductFamily, val positionMode: TradingPositionMode,
                               val settlementAsset: TradingSettlementAsset, val reviewTimezone: String,
                               val status: String, val createdAt: Instant) {
    companion object {
        fun from(book: TradingBookResult) = TradingBookResponse(book.id.value.toString(), book.accountId.value.toString(),
            book.venue, book.productFamily, book.positionMode, book.settlementAsset, book.reviewTimezone,
            book.status.name, book.createdAt)
    }
}
