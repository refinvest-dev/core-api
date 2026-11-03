package com.refinvest.core.tradingreview.port.inbound.account.get

import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId

data class GetTradingAccountQuery(val id: TradingAccountId)
