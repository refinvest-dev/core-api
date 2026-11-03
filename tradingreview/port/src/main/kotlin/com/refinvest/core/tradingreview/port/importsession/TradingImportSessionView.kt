package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.port.ledger.PublishedLedgerSummary
import java.time.Instant

data class TradingImportSessionView(val session: TradingImportSessionResult, val artifacts: List<TradingSourceArtifactResult>,
                                    val published: PublishedLedgerSummary? = null) {
    companion object {
        fun fromDomain(session: TradingImportSession, artifacts: List<TradingSourceArtifact>,
                       published: PublishedLedgerSummary?, now: Instant) = TradingImportSessionView(
            TradingImportSessionResult.fromDomain(session, artifacts),
            artifacts.map { TradingSourceArtifactResult.fromDomain(it, now) }, published)
    }
}
