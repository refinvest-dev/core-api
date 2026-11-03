package com.refinvest.core.tradingreview.port.common.failure

class TradingFailure(val code: String, val kind: TradingFailureKind) : RuntimeException(code) {
    val retryable: Boolean get() = kind == TradingFailureKind.DEPENDENCY_UNAVAILABLE || code == "CONCURRENT_MODIFICATION"
    val userAction: String? get() = when (code) {
        "SOURCE_ARTIFACT_EXPIRED" -> "REUPLOAD_REQUIRED"
        "RESOURCE_DELETED" -> "NONE"
        else -> null
    }
}
