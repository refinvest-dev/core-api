package com.refinvest.core.auth.port.outbound

import java.time.Instant
import java.util.UUID

data class IssuedAuthenticationTokens(
    val issuedAt: Instant,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshJti: UUID,
    val refreshFamilyId: UUID,
    val refreshTokenFingerprint: String,
    val refreshTokenExpiresAt: Instant,
)
