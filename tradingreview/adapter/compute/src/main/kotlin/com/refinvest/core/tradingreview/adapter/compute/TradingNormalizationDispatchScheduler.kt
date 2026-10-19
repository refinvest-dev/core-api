package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.application.TradingNormalizationDispatcher
import com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["refinvest.compute.enabled"], havingValue = "true")
class TradingNormalizationDispatchScheduler(private val dispatcher: TradingNormalizationDispatcher,
                                            private val results: TradingNormalizationResultProcessor) {
    @Scheduled(fixedDelayString = "\${refinvest.compute.dispatch-delay:PT5S}")
    fun dispatch() {
        repeat(20) { if (!dispatcher.dispatchOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.poll-delay:PT5S}")
    fun poll() {
        repeat(20) { if (!dispatcher.pollOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.result-delay:PT5S}")
    fun receiveResults() {
        repeat(20) { if (!results.processOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.ack-delay:PT5S}")
    fun acknowledge() {
        repeat(20) { if (!results.acknowledgeOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.staging-cleanup-delay:PT1M}")
    fun cleanupStaging() {
        results.cleanupObsoleteStaging()
    }
}
