package com.refinvest.core.tradingreview.adapter.web.account

import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

data class CreateTradingAccountRequest(
    val venue: TradingVenue,
    @field:NotEmpty @field:Size(max = 100) val displayName: String,
)
