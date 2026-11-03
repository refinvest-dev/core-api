package com.refinvest.core.tradingreview.port.account.create

import com.refinvest.core.tradingreview.port.account.TradingAccountResult

fun interface CreateTradingAccountUseCase {
    fun execute(command: CreateTradingAccountCommand): TradingAccountResult
}
