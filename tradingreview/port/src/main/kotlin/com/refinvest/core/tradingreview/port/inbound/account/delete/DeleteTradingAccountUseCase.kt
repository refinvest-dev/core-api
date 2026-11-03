package com.refinvest.core.tradingreview.port.inbound.account.delete

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion

fun interface DeleteTradingAccountUseCase {
    fun execute(command: DeleteTradingAccountCommand): TradingDeletion
}
