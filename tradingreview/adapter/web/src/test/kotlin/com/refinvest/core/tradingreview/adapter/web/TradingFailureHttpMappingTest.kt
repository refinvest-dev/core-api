package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.adapter.web.error.TradingReviewExceptionHandler

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TradingFailureHttpMappingTest {
    @Test
    fun existingPublicStatusesAndRecoverySemanticsRemainStable() {
        val cases = listOf(
            Triple("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST, 400),
            Triple("REQUEST_INVALID", TradingFailureKind.INPUT_REJECTED, 422),
            Triple("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND, 404),
            Triple("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE, 410),
            Triple("SOURCE_ARTIFACT_EXPIRED", TradingFailureKind.NO_LONGER_AVAILABLE, 410),
            Triple("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT, 409),
            Triple("IDEMPOTENCY_PAYLOAD_CONFLICT", TradingFailureKind.CONFLICT, 409),
            Triple("FILE_TOO_LARGE", TradingFailureKind.PAYLOAD_TOO_LARGE, 413),
            Triple("UNSUPPORTED_MEDIA_TYPE", TradingFailureKind.UNSUPPORTED_MEDIA, 415),
            Triple("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE, 503),
        )
        for ((code, kind, status) in cases) {
            val response = TradingReviewExceptionHandler().failure(TradingFailure(code, kind))
            assertEquals(status, response.statusCode.value(), code)
            val body = assertNotNull(response.body)
            assertEquals(code, body.code)
            assertEquals(code, body.message)
            assertEquals(code in setOf("CONCURRENT_MODIFICATION", "TEMPORARY_DEPENDENCY_FAILURE"), body.retryable)
            assertEquals(when (code) {
                "RESOURCE_DELETED" -> "NONE"
                "SOURCE_ARTIFACT_EXPIRED" -> "REUPLOAD_REQUIRED"
                else -> null
            }, body.userAction)
            assertEquals(body.userAction != null, body.occurredAt != null)
            assertNotNull(body.traceId.takeIf { it.isNotBlank() })
        }
    }
}
