package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.port.inbound.dispatch.DispatchPendingBacktestsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["refinvest.compute.enabled"], havingValue = "true")
class ComputeDispatchScheduler(
    private val dispatchPendingBacktestsUseCase: DispatchPendingBacktestsUseCase,
) {
    @Scheduled(fixedDelayString = "\${refinvest.compute.dispatch-delay}")
    fun dispatchPendingBacktests() {
        repeat(MAX_DISPATCHES_PER_TICK) {
            if (!dispatchPendingBacktestsUseCase.execute()) return
        }
    }

    private companion object {
        const val MAX_DISPATCHES_PER_TICK = 100
    }
}
