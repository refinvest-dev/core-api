package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.importsession.ExpireTradingImportSourcesUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review", name = ["retention-enabled"], havingValue = "true", matchIfMissing = true)
class TradingImportExpiryScheduler(private val expiry: ExpireTradingImportSourcesUseCase) {
    @Scheduled(fixedDelayString = "\${refinvest.trading-review.expiry-delay:PT5S}")
    fun expire() { expiry.expireDue() }
}
