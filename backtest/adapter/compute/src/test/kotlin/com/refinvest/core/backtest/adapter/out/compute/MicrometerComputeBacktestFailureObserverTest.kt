package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.outbound.observability.ComputeBacktestFailureObservation
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class MicrometerComputeBacktestFailureObserverTest {
    @Test
    fun `counts a Compute failure by error code`() {
        val registry = SimpleMeterRegistry()
        val observer = MicrometerComputeBacktestFailureObserver(registry)

        observer.recordComputeFailure(observation(errorCode = "CALENDAR_RESOLUTION_FAILED"))

        assertEquals(
            1.0,
            registry.get("backtest.runs.failed")
                .tag("source", "compute")
                .tag("error_code", "CALENDAR_RESOLUTION_FAILED")
                .counter()
                .count(),
        )
    }

    @Test
    fun `counts a Compute failure without an error code as unspecified`() {
        val registry = SimpleMeterRegistry()
        val observer = MicrometerComputeBacktestFailureObserver(registry)

        observer.recordComputeFailure(observation(errorCode = null))

        assertEquals(
            1.0,
            registry.get("backtest.runs.failed")
                .tag("source", "compute")
                .tag("error_code", "UNSPECIFIED")
                .counter()
                .count(),
        )
    }

    @Test
    fun `unknown error code cannot create a metric label`() {
        val registry = SimpleMeterRegistry()
        val observer = MicrometerComputeBacktestFailureObserver(registry)

        observer.recordComputeFailure(observation(errorCode = "member-7 user supplied error"))

        assertEquals(
            1.0,
            registry.get("backtest.runs.failed").tag("error_code", "UNSPECIFIED").counter().count(),
        )
        assertEquals(setOf("source", "error_code"), registry.meters.single().id.tags.map { it.key }.toSet())
    }

    private fun observation(errorCode: String?) = ComputeBacktestFailureObservation(
        backtestRunId = BacktestRunId(10L),
        strategyId = StrategyId(7L),
        strategyVersionId = StrategyVersionId(42L),
        errorCode = errorCode,
    )
}
