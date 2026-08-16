package com.refinvest.core.backtest.adapter.web.backtest

import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestRequest
import com.refinvest.core.backtest.adapter.web.backtest.run.RunBacktestResponse
import com.refinvest.core.backtest.domain.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/strategy-versions")
class BacktestController(
    private val runBacktestUseCase: RunBacktestUseCase,
) {
    @PostMapping("/{versionId}/backtests")
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
    }
}
