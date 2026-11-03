package com.refinvest.core.tradingreview.application.importsession

import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.port.inbound.importsession.PublishedLedgerResult
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.PublishedLedgerSummary
import java.time.Instant

internal data class LoadedImportSession(
    val session: TradingImportSession,
    val artifacts: List<TradingSourceArtifact>,
    val published: PublishedLedgerSummary?,
) {
    fun toResult(now: Instant) = TradingImportSessionView.fromDomain(
        session, artifacts, published?.let {
            PublishedLedgerResult(it.revisionId, it.qualityReportJson, it.capabilityJson, it.publishedAt)
        }, now)
}
