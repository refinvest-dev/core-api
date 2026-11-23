package com.refinvest.core.auth.port.inbound.authority

fun interface AuthenticateAuthorityCallerUseCase {
    fun accepts(credential: String): Boolean

    companion object {
        const val CALLER_ID = "trading-review-api"
    }
}
