package com.refinvest.core.tradingreview.port.inbound.account.list

import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult
import com.refinvest.core.tradingreview.port.inbound.pagination.TradingPage

fun interface ListTradingAccountsUseCase {
    fun execute(query: ListTradingAccountsQuery): TradingPage<TradingAccountResult>
}
