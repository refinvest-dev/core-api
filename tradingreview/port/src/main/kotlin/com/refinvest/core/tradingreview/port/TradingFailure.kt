package com.refinvest.core.tradingreview.port

/** Operation failure semantics shared by use cases and capabilities. HTTP mapping belongs to web. */
enum class TradingFailureKind {
    INVALID_REQUEST, INPUT_REJECTED, NOT_FOUND, NO_LONGER_AVAILABLE, CONFLICT,
    PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA, DEPENDENCY_UNAVAILABLE,
}

class TradingFailure(val code: String, val kind: TradingFailureKind) : RuntimeException(code) {
    val retryable: Boolean get() = kind == TradingFailureKind.DEPENDENCY_UNAVAILABLE || code == "CONCURRENT_MODIFICATION"
    val userAction: String? get() = when (code) {
        "SOURCE_ARTIFACT_EXPIRED" -> "REUPLOAD_REQUIRED"
        "RESOURCE_DELETED" -> "NONE"
        else -> null
    }
}
