package com.refinvest.core.tradingreview.application.artifactgrant.redeem

import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantCommand
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantResult
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantUseCase
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantTokens
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantConsumption
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantRedemptionStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Consumption commits before storage is opened; the response owns the unbuffered stream. */
@Service
open class RedeemTradingArtifactGrantService(
    private val tokens: ArtifactGrantTokens,
    private val grants: ArtifactGrantRedemptionStore,
    private val storage: RawArtifactStorage,
    private val clock: Clock,
) : RedeemTradingArtifactGrantUseCase {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: RedeemTradingArtifactGrantCommand): RedeemTradingArtifactGrantResult {
        val hash = tokens.verifiedHash(command.token) ?: unavailable()
        val source = grants.consume(ArtifactGrantConsumption(hash, clock.instant())) ?: unavailable()
        val input = storage.open(source.objectKey, source.wrappedDek, source.keyVersion, source.id)
        return RedeemTradingArtifactGrantResult(input, source.contentLength, source.mediaType)
    }

    private fun unavailable(): Nothing = throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
}
