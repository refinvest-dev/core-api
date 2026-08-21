package com.refinvest.core.auth.adapter.security.oauth

import com.refinvest.core.auth.adapter.security.config.RefInvestSecurityProperties
import com.refinvest.core.auth.adapter.security.cookie.AuthCookieWriter
import com.refinvest.core.auth.port.inbound.auth.login.SocialLoginUseCase
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import java.net.URI

@Component
class SocialLoginSuccessHandler(
    private val providerIdentityNormalizer: ProviderIdentityNormalizer,
    private val socialLoginUseCase: SocialLoginUseCase,
    private val authCookieWriter: AuthCookieWriter,
    private val properties: RefInvestSecurityProperties,
) : AuthenticationSuccessHandler {
    private val webOrigin = URI.create(properties.webOrigin).also { origin ->
        require(origin.scheme in setOf("http", "https") && origin.host != null) {
            "refinvest.security.web-origin must be an absolute HTTP(S) origin"
        }
    }.toString().trimEnd('/')

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val oauth = authentication as? OAuth2AuthenticationToken
            ?: throw IllegalStateException("OAuth2 authentication is required")
        val result = socialLoginUseCase.execute(providerIdentityNormalizer.normalize(oauth))
        authCookieWriter.write(response, result)
        response.sendRedirect(webOrigin + returnTo(request))
    }

    private fun returnTo(request: HttpServletRequest): String {
        val attributeName = request.getParameter("state")
            ?.let(ReturnToAuthorizationRequestResolver::attributeName)
            ?: return "/"
        val session = request.getSession(false) ?: return "/"
        return (session.getAttribute(attributeName) as? String).also { session.removeAttribute(attributeName) } ?: "/"
    }
}
