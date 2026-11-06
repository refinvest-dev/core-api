package com.refinvest.core.tradingreview.port.inbound.account.get

import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult

fun interface GetTradingAccountUseCase {
    fun execute(query: GetTradingAccountQuery): TradingAccountResult
}
