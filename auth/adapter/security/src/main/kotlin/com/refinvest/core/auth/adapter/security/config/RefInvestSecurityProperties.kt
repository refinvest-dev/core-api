package com.refinvest.core.auth.adapter.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("refinvest.security")
data class RefInvestSecurityProperties(
    val issuer: String,
    val audience: String,
    val jwtSecret: String,
    val accessTokenMinutes: Long = 15,
    val refreshTokenDays: Long = 14,
)
