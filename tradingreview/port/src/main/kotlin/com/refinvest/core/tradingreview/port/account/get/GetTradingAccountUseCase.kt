package com.refinvest.core.tradingreview.port.account.get

import com.refinvest.core.tradingreview.port.account.TradingAccountResult

fun interface GetTradingAccountUseCase {
    fun execute(query: GetTradingAccountQuery): TradingAccountResult
}
