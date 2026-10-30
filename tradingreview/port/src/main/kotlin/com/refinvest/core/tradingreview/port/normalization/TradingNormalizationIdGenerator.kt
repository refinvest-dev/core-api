package com.refinvest.core.tradingreview.port.normalization

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId

interface TradingNormalizationIdGenerator {
    fun runId(): NormalizationRunId
    fun dispatchId(): String
    fun attemptToken(): String
}
