package com.refinvest.core.backtest.adapter.web.backtest

import com.refinvest.core.backtest.adapter.web.backtest.get.GetBacktestResultResponse
import com.refinvest.core.backtest.adapter.web.backtest.list.LatestCompletedBacktestRunResponse
import com.refinvest.core.backtest.adapter.web.backtest.list.ListBacktestRunsResponse
import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestRequest
import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestResponse
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.get.GetBacktestResultQuery
import com.refinvest.core.backtest.port.inbound.get.GetBacktestResultUseCase
import com.refinvest.core.backtest.port.inbound.list.GetLatestCompletedBacktestRunUseCase
import com.refinvest.core.backtest.port.inbound.list.ListBacktestRunsQuery
import com.refinvest.core.backtest.port.inbound.list.ListBacktestRunsUseCase
import com.refinvest.core.backtest.port.inbound.run.RunBacktestUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class BacktestController(
    private val runBacktestUseCase: RunBacktestUseCase,
    private val getBacktestResultUseCase: GetBacktestResultUseCase,
    private val listBacktestRunsUseCase: ListBacktestRunsUseCase,
    private val getLatestCompletedBacktestRunUseCase: GetLatestCompletedBacktestRunUseCase,
) {
    @PostMapping("/strategy-versions/{versionId}/backtests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun run(
        @PathVariable versionId: Long,
        @Valid @RequestBody request: RunBacktestRequest,
    ): RunBacktestResponse = try {
        RunBacktestResponse.from(
            runBacktestUseCase.execute(request.toCommand(StrategyVersionId(versionId))),
        )
    } catch (exception: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
    } catch (exception: NoSuchElementException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
    }

    @GetMapping("/backtest-runs/{runId}")
    fun pollStatus(@PathVariable runId: Long): GetBacktestResultResponse =
        getBacktestResultUseCase.execute(GetBacktestResultQuery(BacktestRunId(runId)))
            ?.let(GetBacktestResultResponse::from)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest run not found")

    @GetMapping("/strategies/{strategyId}/backtest-runs")
    fun list(
        @PathVariable strategyId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ListBacktestRunsResponse = try {
        ListBacktestRunsResponse.from(
            listBacktestRunsUseCase.execute(ListBacktestRunsQuery(StrategyId(strategyId), page, size)),
        )
    } catch (exception: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
    } catch (exception: NoSuchElementException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
    }

    @GetMapping("/strategy-versions/{versionId}/backtest-runs/latest-completed")
    fun getLatestCompleted(@PathVariable versionId: Long): LatestCompletedBacktestRunResponse = try {
        LatestCompletedBacktestRunResponse.from(
            getLatestCompletedBacktestRunUseCase.execute(StrategyVersionId(versionId)),
        )
    } catch (exception: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
    } catch (exception: NoSuchElementException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
    }
}
