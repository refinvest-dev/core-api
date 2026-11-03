package com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion

fun interface DeleteTradingSourceArtifactUseCase {
    fun execute(command: DeleteTradingSourceArtifactCommand): TradingDeletion
}
