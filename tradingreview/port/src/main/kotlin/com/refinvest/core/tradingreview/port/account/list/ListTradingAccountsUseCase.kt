package com.refinvest.core.tradingreview.port.account.list

import com.refinvest.core.tradingreview.port.account.TradingAccountResult
import com.refinvest.core.tradingreview.port.pagination.TradingPage

fun interface ListTradingAccountsUseCase {
    fun execute(query: ListTradingAccountsQuery): TradingPage<TradingAccountResult>
}
