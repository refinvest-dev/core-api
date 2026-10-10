package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.port.TradingFailure
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.util.UUID

data class TradingErrorResponse(val code: String, val message: String, val traceId: String, val retryable: Boolean)

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [TradingAccountController::class, TradingBookController::class,
    TradingDeletionController::class])
class TradingReviewExceptionHandler {
    @ExceptionHandler(TradingFailure::class)
    fun failure(error: TradingFailure): ResponseEntity<TradingErrorResponse> =
        response(HttpStatus.valueOf(error.httpStatus), error.code, error.code == "CONCURRENT_MODIFICATION")

    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class, MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class)
    fun invalid(@Suppress("UNUSED_PARAMETER") error: Exception): ResponseEntity<TradingErrorResponse> =
        response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", false)

    private fun response(status: HttpStatus, code: String, retryable: Boolean) =
        ResponseEntity.status(status).body(TradingErrorResponse(code, code, UUID.randomUUID().toString(), retryable))
}
