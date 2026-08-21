package com.refinvest.core.auth.adapter.web

import com.refinvest.core.auth.adapter.security.cookie.AuthCookieWriter
import com.refinvest.core.auth.adapter.web.auth.me.GetCurrentMemberResponse
import com.refinvest.core.auth.port.inbound.auth.me.GetCurrentMemberUseCase
import com.refinvest.core.auth.port.inbound.auth.logout.LogoutCommand
import com.refinvest.core.auth.port.inbound.auth.logout.LogoutUseCase
import com.refinvest.core.auth.port.inbound.auth.session.RefreshSessionCommand
import com.refinvest.core.auth.port.inbound.auth.session.RefreshSessionUseCase
import com.refinvest.core.auth.port.outbound.RefreshTokenParser
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class AuthController(
    private val refreshTokenParser: RefreshTokenParser,
    private val getCurrentMemberUseCase: GetCurrentMemberUseCase,
    private val refreshSessionUseCase: RefreshSessionUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val authCookieWriter: AuthCookieWriter,
) {
    @GetMapping("/auth/me")
    fun me(): GetCurrentMemberResponse = getCurrentMemberUseCase.execute()
        ?.let(GetCurrentMemberResponse::from)
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated member is unavailable")

    @GetMapping("/auth/csrf")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun csrf(csrfToken: CsrfToken) {
        csrfToken.token
    }

    @PostMapping("/auth/refresh")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun refresh(
        @CookieValue(AuthCookieWriter.REFRESH_COOKIE_NAME, required = false) rawRefreshToken: String?,
        response: HttpServletResponse,
    ) {
        val claims = rawRefreshToken?.let(refreshTokenParser::parse)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid")
        val result = refreshSessionUseCase.execute(
            RefreshSessionCommand(
                jti = claims.jti,
                memberId = claims.memberId,
                familyId = claims.familyId,
                tokenFingerprint = claims.tokenFingerprint,
            ),
        ) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid")
        authCookieWriter.write(response, result)
    }

    @PostMapping("/auth/logout")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(
        @CookieValue(AuthCookieWriter.REFRESH_COOKIE_NAME, required = false) rawRefreshToken: String?,
        response: HttpServletResponse,
    ) {
        rawRefreshToken?.let(refreshTokenParser::parse)?.let { claims ->
            logoutUseCase.execute(LogoutCommand(claims.familyId))
        }
        authCookieWriter.clear(response)
    }
}
