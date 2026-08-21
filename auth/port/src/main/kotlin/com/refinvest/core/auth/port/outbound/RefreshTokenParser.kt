package com.refinvest.core.auth.port.outbound

fun interface RefreshTokenParser {
    fun parse(rawToken: String): RefreshTokenClaims?
}
