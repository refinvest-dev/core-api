package com.refinvest.core.tradingreview.port.analytics.input

import java.io.OutputStream
import java.io.InputStream

interface TradingAnalyticsRevisionReader {
    fun find(revisionId: String, ownerId: Long): AnalyticsRevisionSource?
    fun visitRecords(revisionId: String, accept: (String) -> Unit)
    fun visitEvidenceReferences(revisionId: String, accept: (String) -> Unit)
    fun visitEngineInputs(revisionId: String, accept: (String) -> Unit)
}
