package com.refinvest.core.config.web

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(exception: ResponseStatusException): ResponseEntity<ApiErrorResponse> =
        errorResponse(exception.statusCode, exception.reason ?: exception.statusCode.reasonPhrase())

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleInvalidRequest(exception: Exception): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.BAD_REQUEST, "Invalid request")

    private fun errorResponse(status: HttpStatusCode, message: String): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(status).body(ApiErrorResponse(status.errorCode(), message))

    private fun HttpStatusCode.errorCode(): String = when (value()) {
        HttpStatus.BAD_REQUEST.value() -> "BAD_REQUEST"
        HttpStatus.UNAUTHORIZED.value() -> "UNAUTHORIZED"
        HttpStatus.FORBIDDEN.value() -> "FORBIDDEN"
        HttpStatus.NOT_FOUND.value() -> "NOT_FOUND"
        HttpStatus.SERVICE_UNAVAILABLE.value() -> "SERVICE_UNAVAILABLE"
        else -> "HTTP_${value()}"
    }

    private fun HttpStatusCode.reasonPhrase(): String =
        HttpStatus.resolve(value())?.reasonPhrase ?: "Request failed"
}
