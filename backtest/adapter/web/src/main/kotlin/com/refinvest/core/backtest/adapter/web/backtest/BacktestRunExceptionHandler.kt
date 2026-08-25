package com.refinvest.core.backtest.adapter.web.backtest

import com.refinvest.core.backtest.port.inbound.run.BacktestRunRejectedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [BacktestController::class])
class BacktestRunExceptionHandler {
    @ExceptionHandler(BacktestRunRejectedException::class)
    fun handle(exception: BacktestRunRejectedException): ResponseEntity<BacktestRunErrorResponse> =
        ResponseEntity.status(
            when (exception.code) {
                "BACKTEST_MONTHLY_LIMIT_EXCEEDED", "BACKTEST_CONCURRENCY_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS
                else -> HttpStatus.FORBIDDEN
            },
        ).body(BacktestRunErrorResponse(exception.code, requireNotNull(exception.message)))
}

data class BacktestRunErrorResponse(
    val code: String,
    val message: String,
)
