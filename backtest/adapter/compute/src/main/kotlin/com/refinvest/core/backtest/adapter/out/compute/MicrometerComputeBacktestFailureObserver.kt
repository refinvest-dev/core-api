package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.port.outbound.observability.BacktestFailureObserver
import com.refinvest.core.backtest.port.outbound.observability.ComputeBacktestFailureObservation
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class MicrometerComputeBacktestFailureObserver(
    private val meterRegistry: MeterRegistry,
) : BacktestFailureObserver {
    override fun recordComputeFailure(observation: ComputeBacktestFailureObservation) {
        val errorCode = observation.errorCode?.takeIf { it in KNOWN_ERROR_CODES } ?: UNSPECIFIED_ERROR_CODE

        logger.warn("event={} runId={} status={} errorCode={}",
            FAILED_EVENT, observation.backtestRunId.value, TERMINAL_STATUS, errorCode)

        meterRegistry.counter(
            FAILED_METRIC,
            "source", "compute",
            "error_code", errorCode,
        ).increment()
    }

    private companion object {
        const val FAILED_EVENT = "backtest_run_failed"
        const val TERMINAL_STATUS = "FAILED"
        const val UNSPECIFIED_ERROR_CODE = "UNSPECIFIED"
        const val FAILED_METRIC = "backtest.runs.failed"
        val KNOWN_ERROR_CODES = setOf(
            "MISSING_REQUIRED_DATA", "DATASET_CORRUPTION", "CALENDAR_RESOLUTION_FAILED",
            "PRICE_DATA_MISSING", "DSL_INVALID",
        )

        val logger = LoggerFactory.getLogger(MicrometerComputeBacktestFailureObserver::class.java)
    }
}
