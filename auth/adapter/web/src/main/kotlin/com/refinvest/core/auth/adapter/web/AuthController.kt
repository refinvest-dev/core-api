package com.refinvest.core.auth.adapter.web

import com.refinvest.core.auth.adapter.security.cookie.AuthCookieWriter
import com.refinvest.core.auth.adapter.web.auth.me.GetCurrentMemberResponse
import com.refinvest.core.auth.adapter.web.auth.subscription.UpgradeSubscriptionResponse
import com.refinvest.core.auth.adapter.web.auth.usage.GetUsageResponse
import com.refinvest.core.auth.port.inbound.me.GetCurrentMemberResult
import com.refinvest.core.auth.port.inbound.me.GetCurrentMemberUseCase
import com.refinvest.core.auth.port.inbound.logout.LogoutCommand
import com.refinvest.core.auth.port.inbound.logout.LogoutUseCase
import com.refinvest.core.auth.port.inbound.session.RefreshSessionCommand
import com.refinvest.core.auth.port.inbound.session.RefreshSessionUseCase
import com.refinvest.core.auth.port.outbound.token.RefreshTokenParser
import com.refinvest.core.subscription.port.inbound.usage.GetUsageQuery
import com.refinvest.core.subscription.port.inbound.usage.GetUsageUseCase
import com.refinvest.core.subscription.port.inbound.upgrade.UpgradeSubscriptionCommand
import com.refinvest.core.subscription.port.inbound.upgrade.UpgradeSubscriptionUseCase
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
    private val getUsageUseCase: GetUsageUseCase,
    private val upgradeSubscriptionUseCase: UpgradeSubscriptionUseCase,
    private val refreshSessionUseCase: RefreshSessionUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val authCookieWriter: AuthCookieWriter,
) {
    @GetMapping("/auth/me")
    fun me(): GetCurrentMemberResponse = GetCurrentMemberResponse.from(currentMember())

    @GetMapping("/me/usage")
    fun usage(): GetUsageResponse = GetUsageResponse.from(
        getUsageUseCase.execute(GetUsageQuery(currentMember().memberId)),
    )

    @PostMapping("/me/subscription/upgrade")
    fun upgradeSubscription(): UpgradeSubscriptionResponse = UpgradeSubscriptionResponse.from(
        upgradeSubscriptionUseCase.execute(UpgradeSubscriptionCommand(currentMember().memberId)),
    )

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

    private fun currentMember(): GetCurrentMemberResult = getCurrentMemberUseCase.execute()
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated member is unavailable")
}
