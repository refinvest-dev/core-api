package com.refinvest.core.tradingreview.port.account.delete

import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId

data class DeleteTradingAccountCommand(val id: TradingAccountId, val key: String)
