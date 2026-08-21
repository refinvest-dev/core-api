package com.refinvest.core.auth.adapter.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("refinvest.security")
data class RefInvestSecurityProperties(
    val issuer: String,
    val audience: String,
    val jwtSecret: String,
    val webOrigin: String,
    val accessTokenMinutes: Long = 15,
    val refreshTokenDays: Long = 14,
    val cookieSecure: Boolean = true,
    val cookieSameSite: String = "Lax",
)
