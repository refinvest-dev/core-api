package com.refinvest.core.auth.adapter.security.cookie

import com.refinvest.core.auth.adapter.security.config.RefInvestSecurityProperties
import com.refinvest.core.auth.port.inbound.login.SocialLoginResult
import com.refinvest.core.auth.port.inbound.session.RefreshSessionResult
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class AuthCookieWriter(
    private val properties: RefInvestSecurityProperties,
    private val clock: Clock,
) {
    fun write(response: HttpServletResponse, result: SocialLoginResult) {
        write(response, result.accessToken, result.accessTokenExpiresAt, result.refreshToken, result.refreshTokenExpiresAt)
    }

    fun write(response: HttpServletResponse, result: RefreshSessionResult) {
        write(response, result.accessToken, result.accessTokenExpiresAt, result.refreshToken, result.refreshTokenExpiresAt)
    }

    fun clear(response: HttpServletResponse) {
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie("", null).toString())
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", null).toString())
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie().toString())
    }

    private fun write(
        response: HttpServletResponse,
        accessToken: String,
        accessTokenExpiresAt: Instant,
        refreshToken: String,
        refreshTokenExpiresAt: Instant,
    ) {
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie(accessToken, accessTokenExpiresAt).toString())
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(refreshToken, refreshTokenExpiresAt).toString())
    }

    private fun accessCookie(value: String, expiresAt: Instant?): ResponseCookie = cookie(ACCESS_COOKIE_NAME, value, "/", expiresAt)

    private fun refreshCookie(value: String, expiresAt: Instant?): ResponseCookie = cookie(REFRESH_COOKIE_NAME, value, "/auth", expiresAt)

    private fun sessionCookie(): ResponseCookie = cookie(SERVLET_SESSION_COOKIE_NAME, "", "/", null)

    private fun cookie(name: String, value: String, path: String, expiresAt: Instant?): ResponseCookie = ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(properties.cookieSecure)
        .sameSite(properties.cookieSameSite)
        .path(path)
        .maxAge(maxAge(expiresAt))
        .build()

    private fun maxAge(expiresAt: Instant?): Duration = expiresAt?.let { expires ->
        Duration.ofSeconds((expires.epochSecond - clock.instant().epochSecond).coerceAtLeast(0))
    } ?: Duration.ZERO

    companion object {
        const val ACCESS_COOKIE_NAME = "REFINVEST_ACCESS_TOKEN"
        const val REFRESH_COOKIE_NAME = "REFINVEST_REFRESH_TOKEN"
        const val SERVLET_SESSION_COOKIE_NAME = "JSESSIONID"
    }
}
