package com.refinvest.core.auth.port.outbound.delegation

class DelegationSigningException(val reason: Reason) : RuntimeException("Owner query delegation unavailable") {
    enum class Reason { INVALID_INPUT, INVALID_KEY, SIGNING_UNAVAILABLE }
}
