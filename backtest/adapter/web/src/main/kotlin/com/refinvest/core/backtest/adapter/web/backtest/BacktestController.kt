package com.refinvest.core.backtest.adapter.web.backtest

import com.refinvest.core.backtest.adapter.web.backtest.get.GetBacktestResultResponse
import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestRequest
import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestResponse
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultQuery
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultUseCase
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class BacktestController(
    private val runBacktestUseCase: RunBacktestUseCase,
    private val getBacktestResultUseCase: GetBacktestResultUseCase,
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
}
