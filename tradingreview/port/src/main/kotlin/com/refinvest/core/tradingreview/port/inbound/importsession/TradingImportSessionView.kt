package com.refinvest.core.tradingreview.port.inbound.importsession

import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import java.time.Instant

data class TradingImportSessionView(val session: TradingImportSessionResult, val artifacts: List<TradingSourceArtifactResult>,
                                    val published: PublishedLedgerResult? = null) {
    companion object {
        fun fromDomain(session: TradingImportSession, artifacts: List<TradingSourceArtifact>,
                       published: PublishedLedgerResult?, now: Instant) = TradingImportSessionView(
            TradingImportSessionResult.fromDomain(session, artifacts),
            artifacts.map { TradingSourceArtifactResult.fromDomain(it, now) }, published)
    }
}
