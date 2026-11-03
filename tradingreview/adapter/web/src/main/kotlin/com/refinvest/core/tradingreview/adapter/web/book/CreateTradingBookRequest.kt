package com.refinvest.core.tradingreview.adapter.web.book

import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

data class CreateTradingBookRequest(
    @field:NotEmpty @field:Size(max = 100) val displayName: String,
    val productFamily: TradingProductFamily,
    val positionMode: TradingPositionMode,
    val settlementAsset: TradingSettlementAsset,
    @field:NotBlank val reviewTimezone: String,
)
