package com.refinvest.core.auth.port.inbound.session

import java.time.Instant

data class RefreshSessionResult(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
