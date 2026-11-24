package com.refinvest.core.tradingreview.adapter.web.ownerquery

import com.refinvest.core.shared.infrastructure.http.TraceContextHeaders
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataFailure
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResource
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResult
import com.refinvest.core.tradingreview.port.inbound.ownerquery.OwnerMetadataQuery
import com.refinvest.core.tradingreview.port.inbound.ownerquery.ProxyOwnerMetadataUseCase
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import java.time.Instant
import java.util.concurrent.CancellationException

object OwnerMetadataWebProxy {
    fun respond(
        proxy: ProxyOwnerMetadataUseCase,
        resource: OwnerMetadataResource,
        id: Long,
        request: HttpServletRequest,
    ): Any {
        val trace = trace(request)
        if (request.method != "GET") return ResponseEntity.status(404).header("Cache-Control", "no-store").build<Void>()

        if (request.queryString != null || request.contentLengthLong != 0L && request.contentLengthLong != -1L ||
            request.getHeader("Transfer-Encoding") != null || request.getHeader("Content-Encoding") != null) {
            return error(OwnerMetadataFailure.INVALID_REQUEST, trace.traceId)
        }

        val call = proxy.execute(OwnerMetadataQuery(resource, id.toString(), trace.parent, trace.state))
        val deferred = DeferredResult<ResponseEntity<*>>(call.timeout.toMillis().coerceAtLeast(1))
        deferred.onTimeout {
            deferred.setResult(error(OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED, trace.traceId))
            call.cancel()
        }
        deferred.onError { call.cancel() }
        deferred.onCompletion { call.cancel() }

        call.result.whenComplete { result, failure ->
            if (failure !is CancellationException) {
                val response = if (failure != null || result == null) {
                    error(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR, trace.traceId)
                } else when (result) {
                    is OwnerMetadataResult.Account -> ResponseEntity.ok().header("Cache-Control", "no-store").body(
                        AccountResponse(result.id, result.venue, result.displayName, result.status, result.createdAt),
                    )
                    is OwnerMetadataResult.Book -> ResponseEntity.ok().header("Cache-Control", "no-store").body(
                        BookResponse(
                            result.id, result.tradingAccountId, result.venue, result.productFamily, result.positionMode,
                            result.settlementAsset, result.reviewTimezone, result.status, result.createdAt,
                        ),
                    )
                    is OwnerMetadataResult.Failure -> error(result.kind, trace.traceId)
                }
                deferred.setResult(response)
            }
        }
        return deferred
    }

    fun invalid(request: HttpServletRequest): ResponseEntity<*> = error(OwnerMetadataFailure.INVALID_REQUEST, trace(request).traceId)

    fun isMetadataRead(request: HttpServletRequest): Boolean = request.method in setOf("GET", "HEAD") &&
        Regex("/trading-(accounts|books)/[^/]+").matches(request.requestURI.removePrefix(request.contextPath))

    private fun trace(request: HttpServletRequest): TraceContextHeaders = TraceContextHeaders.start(
        request.getHeaders("traceparent").toList(), request.getHeaders("tracestate").toList(),
    )

    private fun error(kind: OwnerMetadataFailure, traceId: String): ResponseEntity<ErrorResponse> {
        val (status, message, retryable) = when (kind) {
            OwnerMetadataFailure.INVALID_REQUEST -> Triple(400, "Invalid request", false)
            OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR -> Triple(502, "Upstream service error", false)
            OwnerMetadataFailure.MEMBER_ACCESS_DENIED -> Triple(403, "Access denied", false)
            OwnerMetadataFailure.RESOURCE_NOT_FOUND -> Triple(404, "Resource not found", false)
            OwnerMetadataFailure.RESOURCE_DELETED -> Triple(410, "Resource deleted", false)
            OwnerMetadataFailure.AUTHORITY_UNAVAILABLE, OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE ->
                Triple(503, "Service unavailable", true)
            OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED -> Triple(504, "Request deadline exceeded", true)
        }

        return ResponseEntity.status(status).header("Cache-Control", "no-store")
            .body(ErrorResponse(kind.name, message, traceId, retryable))
    }

    data class AccountResponse(
        val id: String,
        val venue: String,
        val displayName: String,
        val status: String,
        val createdAt: Instant,
    )

    data class BookResponse(
        val id: String,
        val tradingAccountId: String,
        val venue: String,
        val productFamily: String,
        val positionMode: String,
        val settlementAsset: String,
        val reviewTimezone: String,
        val status: String,
        val createdAt: Instant,
    )

    data class ErrorResponse(val code: String, val message: String, val traceId: String, val retryable: Boolean)
}
