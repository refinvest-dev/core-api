package com.refinvest.core.tradingreview.port.inbound.importsession.delete

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion

fun interface DeleteTradingImportSessionUseCase {
    fun execute(command: DeleteTradingImportSessionCommand): TradingDeletion
}
