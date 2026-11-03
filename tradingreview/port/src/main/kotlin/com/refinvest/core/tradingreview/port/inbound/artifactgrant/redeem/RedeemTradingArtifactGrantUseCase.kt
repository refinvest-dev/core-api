package com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem

fun interface RedeemTradingArtifactGrantUseCase {
    fun execute(command: RedeemTradingArtifactGrantCommand): RedeemTradingArtifactGrantResult
}
