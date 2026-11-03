package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.port.inbound.normalization.acknowledge.AcknowledgeTradingNormalizationResultUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.cleanup.CleanupTradingNormalizationStagingUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.dispatch.DispatchTradingNormalizationUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.poll.PollTradingNormalizationUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.receive.ReceiveTradingNormalizationResultUseCase

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["refinvest.compute.enabled"], havingValue = "true")
class TradingNormalizationDispatchScheduler(
    private val dispatcher: DispatchTradingNormalizationUseCase,
    private val polling: PollTradingNormalizationUseCase,
    private val results: ReceiveTradingNormalizationResultUseCase,
    private val acknowledgements: AcknowledgeTradingNormalizationResultUseCase,
    private val staging: CleanupTradingNormalizationStagingUseCase,
) {
    @Scheduled(fixedDelayString = "\${refinvest.compute.dispatch-delay:PT5S}")
    fun dispatch() {
        repeat(20) { if (!dispatcher.dispatchOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.poll-delay:PT5S}")
    fun poll() {
        repeat(20) { if (!polling.pollOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.result-delay:PT5S}")
    fun receiveResults() {
        repeat(20) { if (!results.processOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.ack-delay:PT5S}")
    fun acknowledge() {
        repeat(20) { if (!acknowledgements.acknowledgeOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.staging-cleanup-delay:PT1M}")
    fun cleanupStaging() {
        staging.cleanupObsoleteStaging()
    }
}
