package com.refinvest.core.tradingreview.port.normalization

import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingNormalizationRunReader {
    fun findRun(id: NormalizationRunId): NormalizationRun?
    fun findActive(sessionId: TradingImportSessionId): NormalizationRun?
}
