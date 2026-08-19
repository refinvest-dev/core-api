package com.refinvest.core.backtest.adapter.web.backtest

import com.refinvest.core.backtest.adapter.web.backtest.poll.PollBacktestStatusResponse
import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestRequest
import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestResponse
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.poll.PollBacktestStatusQuery
import com.refinvest.core.backtest.port.inbound.backtest.poll.PollBacktestStatusUseCase
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
    private val pollBacktestStatusUseCase: PollBacktestStatusUseCase,
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
    fun pollStatus(@PathVariable runId: Long): PollBacktestStatusResponse =
        pollBacktestStatusUseCase.execute(PollBacktestStatusQuery(BacktestRunId(runId)))
            ?.let(PollBacktestStatusResponse::from)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest run not found")
}
