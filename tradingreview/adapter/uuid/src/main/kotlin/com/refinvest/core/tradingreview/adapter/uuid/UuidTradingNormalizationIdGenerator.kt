package com.refinvest.core.tradingreview.adapter.uuid

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.tradingreview.port.normalization.TradingNormalizationIdGenerator
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class UuidTradingNormalizationIdGenerator : TradingNormalizationIdGenerator {
    override fun runId() = NormalizationRunId(UUID.randomUUID().toString())
    override fun dispatchId() = UUID.randomUUID().toString()
    override fun attemptToken() = UUID.randomUUID().toString()
}
