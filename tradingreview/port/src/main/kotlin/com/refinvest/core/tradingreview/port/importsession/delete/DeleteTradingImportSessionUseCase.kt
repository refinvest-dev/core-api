package com.refinvest.core.tradingreview.port.importsession.delete

import com.refinvest.core.tradingreview.port.deletion.TradingDeletion

fun interface DeleteTradingImportSessionUseCase {
    fun execute(command: DeleteTradingImportSessionCommand): TradingDeletion
}
