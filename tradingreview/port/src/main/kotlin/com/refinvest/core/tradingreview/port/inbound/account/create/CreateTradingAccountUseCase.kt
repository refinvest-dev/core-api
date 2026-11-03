package com.refinvest.core.tradingreview.port.inbound.account.create

import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult

fun interface CreateTradingAccountUseCase {
    fun execute(command: CreateTradingAccountCommand): TradingAccountResult
}
