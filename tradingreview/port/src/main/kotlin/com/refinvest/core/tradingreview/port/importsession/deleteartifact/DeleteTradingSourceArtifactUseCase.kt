package com.refinvest.core.tradingreview.port.importsession.deleteartifact

import com.refinvest.core.tradingreview.port.deletion.TradingDeletion

fun interface DeleteTradingSourceArtifactUseCase {
    fun execute(command: DeleteTradingSourceArtifactCommand): TradingDeletion
}
