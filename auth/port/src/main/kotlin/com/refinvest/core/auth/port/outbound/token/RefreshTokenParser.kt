package com.refinvest.core.auth.port.outbound.token

fun interface RefreshTokenParser {
    fun parse(rawToken: String): RefreshTokenClaims?
}
