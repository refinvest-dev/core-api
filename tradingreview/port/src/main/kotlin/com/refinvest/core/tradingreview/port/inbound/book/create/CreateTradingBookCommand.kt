package com.refinvest.core.tradingreview.port.inbound.book.create

import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset

data class CreateTradingBookCommand(
    val accountId: TradingAccountId,
    val displayName: String,
    val productFamily: TradingProductFamily,
    val positionMode: TradingPositionMode,
    val settlementAsset: TradingSettlementAsset,
    val reviewTimezone: String,
    val key: String,
)
