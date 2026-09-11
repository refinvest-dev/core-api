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
        val errorCode = observation.errorCode?.takeIf(String::isNotBlank) ?: UNSPECIFIED_ERROR_CODE

        logger.atWarn()
            .addKeyValue("event", FAILED_EVENT)
            .addKeyValue("terminal_status", TERMINAL_STATUS)
            .addKeyValue("error_code", errorCode)
            .addKeyValue("backtest_run_id", observation.backtestRunId.value)
            .addKeyValue("strategy_id", observation.strategyId.value)
            .addKeyValue("strategy_version_id", observation.strategyVersionId.value)
            .log("Compute backtest run reached a terminal failed state")

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

        val logger = LoggerFactory.getLogger(MicrometerComputeBacktestFailureObserver::class.java)
    }
}
