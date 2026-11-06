package com.refinvest.core.tradingreview.port.inbound.account.create

import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue

data class CreateTradingAccountCommand(val venue: TradingVenue, val displayName: String, val key: String)
