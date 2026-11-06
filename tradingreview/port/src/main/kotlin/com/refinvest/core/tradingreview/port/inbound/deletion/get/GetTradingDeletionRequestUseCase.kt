package com.refinvest.core.tradingreview.port.inbound.deletion.get

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion

fun interface GetTradingDeletionRequestUseCase {
    fun execute(query: GetTradingDeletionRequestQuery): TradingDeletion
}
