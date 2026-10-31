package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.deletion.TradingDeletionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review", name = ["deletion-enabled"], havingValue = "true", matchIfMissing = true)
class TradingDeletionWorker(private val deletion: TradingDeletionUseCase) {
    @Scheduled(fixedDelay = 5000)
    fun purgePending() = deletion.purgePending()
}
