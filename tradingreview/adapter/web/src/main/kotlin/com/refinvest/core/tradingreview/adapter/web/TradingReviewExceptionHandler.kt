package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingFailureKind
import java.time.Instant
import java.util.UUID
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

data class TradingErrorResponse(val code: String, val message: String, val traceId: String, val retryable: Boolean,
                                val userAction: String? = null, val occurredAt: Instant? = null)

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [TradingAccountController::class, TradingBookController::class,
    TradingDeletionController::class, TradingBookImportController::class, TradingImportSessionController::class, TradingSourceArtifactController::class, TradingArtifactGrantController::class])
class TradingReviewExceptionHandler {
    @ExceptionHandler(TradingFailure::class)
    fun failure(error: TradingFailure): ResponseEntity<TradingErrorResponse> =
        response(when (error.kind) {
            TradingFailureKind.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
            TradingFailureKind.INPUT_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY
            TradingFailureKind.NOT_FOUND -> HttpStatus.NOT_FOUND
            TradingFailureKind.NO_LONGER_AVAILABLE -> HttpStatus.GONE
            TradingFailureKind.CONFLICT -> HttpStatus.CONFLICT
            TradingFailureKind.PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE
            TradingFailureKind.UNSUPPORTED_MEDIA -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
            TradingFailureKind.DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        }, error.code, error.retryable, error.userAction)

    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class, MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class)
    fun invalid(@Suppress("UNUSED_PARAMETER") error: Exception): ResponseEntity<TradingErrorResponse> =
        response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", false)

    private fun response(status: HttpStatus, code: String, retryable: Boolean, userAction: String? = null) =
        ResponseEntity.status(status).body(TradingErrorResponse(code, code, UUID.randomUUID().toString(),
            retryable, userAction, if (userAction != null) Instant.now() else null))
}
