package com.refinvest.core.tradingreview.port.account.delete

import com.refinvest.core.tradingreview.port.deletion.TradingDeletion

fun interface DeleteTradingAccountUseCase {
    fun execute(command: DeleteTradingAccountCommand): TradingDeletion
}
