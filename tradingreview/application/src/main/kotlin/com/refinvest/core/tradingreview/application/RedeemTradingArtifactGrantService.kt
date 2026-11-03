package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantCommand
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantResult
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantUseCase
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactReadGrantRedeemer

import org.springframework.stereotype.Service

/** Redemption keeps its existing atomic consume, then returns the storage stream without buffering. */
@Service
class RedeemTradingArtifactGrantService(private val grants: ArtifactReadGrantRedeemer) : RedeemTradingArtifactGrantUseCase {
    override fun execute(command: RedeemTradingArtifactGrantCommand): RedeemTradingArtifactGrantResult {
        val redeemed = grants.redeem(command.token)
        return RedeemTradingArtifactGrantResult(redeemed.input, redeemed.contentLength, redeemed.mediaType)
    }
}
