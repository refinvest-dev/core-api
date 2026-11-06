package com.refinvest.core.auth.adapter.web.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("refinvest.security")
data class AuthWebProperties(
    val webOrigin: String,
    val cookieSecure: Boolean = true,
    val cookieSameSite: String = "Lax",
)
