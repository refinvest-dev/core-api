package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.application.TradingNormalizationDispatcher
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["refinvest.compute.enabled"], havingValue = "true")
class TradingNormalizationDispatchScheduler(private val dispatcher: TradingNormalizationDispatcher) {
    @Scheduled(fixedDelayString = "\${refinvest.compute.dispatch-delay:PT5S}")
    fun dispatch() {
        repeat(20) { if (!dispatcher.dispatchOne()) return }
    }

    @Scheduled(fixedDelayString = "\${refinvest.compute.poll-delay:PT5S}")
    fun poll() {
        repeat(20) { if (!dispatcher.pollOne()) return }
    }
}
