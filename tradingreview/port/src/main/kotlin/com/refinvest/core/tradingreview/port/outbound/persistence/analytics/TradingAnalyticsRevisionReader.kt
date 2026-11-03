package com.refinvest.core.tradingreview.port.outbound.persistence.analytics

interface TradingAnalyticsRevisionReader {
    fun find(revisionId: String, ownerId: Long): AnalyticsRevisionSource?
    fun visitRecords(revisionId: String, accept: (String) -> Unit)
    fun visitEvidenceReferences(revisionId: String, accept: (String) -> Unit)
    fun visitEngineInputs(revisionId: String, accept: (String) -> Unit)
}
