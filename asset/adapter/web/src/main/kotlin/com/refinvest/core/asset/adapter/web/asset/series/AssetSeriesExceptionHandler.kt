package com.refinvest.core.asset.adapter.web.asset.series

import com.refinvest.core.asset.adapter.web.asset.AssetController
import com.refinvest.core.asset.port.outbound.compute.SeriesDataErrorException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AssetController::class])
class AssetSeriesExceptionHandler {
    @ExceptionHandler(SeriesDataErrorException::class)
    fun handle(exception: SeriesDataErrorException): ResponseEntity<SeriesDataErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            SeriesDataErrorResponse(
                message = requireNotNull(exception.message),
                errorCode = exception.errorCode.name,
            ),
        )
}
