package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.port.inbound.normalization.cleanup.CleanupTradingResultSpoolUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class NormalizationResultSpoolSweeper(private val cleanup: CleanupTradingResultSpoolUseCase) {
    @Scheduled(fixedDelayString = "\${refinvest.trading-review.normalization.delivery.spool-sweep-delay-ms:3600000}")
    fun sweep(): Int = cleanup.cleanup()
}
