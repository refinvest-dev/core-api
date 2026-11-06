package com.refinvest.core.tradingreview.port.inbound.deletion.remediate

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion

fun interface RemediateTradingDeletionUseCase {
    fun execute(command: RemediateTradingDeletionCommand): TradingDeletion
}
