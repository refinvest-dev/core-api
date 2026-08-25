package com.refinvest.core.auth.port.inbound.login

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

data class SocialLoginResult(
    val memberId: MemberId,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
