package com.refinvest.core.tradingreview.port.common.failure

enum class TradingFailureKind {
    INVALID_REQUEST, INPUT_REJECTED, NOT_FOUND, NO_LONGER_AVAILABLE, CONFLICT,
    PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA, DEPENDENCY_UNAVAILABLE,
}
