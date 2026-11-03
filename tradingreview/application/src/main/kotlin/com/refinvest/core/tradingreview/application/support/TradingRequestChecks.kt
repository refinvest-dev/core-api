package com.refinvest.core.tradingreview.application.support

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun validateIdempotencyKey(key: String) {
    if (key.length !in 1..128 || !KEY.matches(key)) throw TradingFailure("IDEMPOTENCY_KEY_INVALID", TradingFailureKind.INVALID_REQUEST)
}

internal fun requestFingerprint(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(0)
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun missingResource(deleted: Boolean): TradingFailure =
    if (deleted) TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE) else TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)

internal fun validatePage(page: Int, size: Int) {
    if (page < 0 || size !in 1..100) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
}

private val KEY = Regex("[A-Za-z0-9._~-]+")
