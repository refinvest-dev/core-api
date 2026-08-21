package com.refinvest.core.auth.adapter.security.jwt

import com.refinvest.core.auth.adapter.security.cookie.AuthCookieWriter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver

class AccessCookieBearerTokenResolver : BearerTokenResolver {
    override fun resolve(request: HttpServletRequest): String? {
        if (isUnauthenticatedEndpoint(request.requestURI)) return null

        return request.cookies
            ?.firstOrNull { it.name == AuthCookieWriter.ACCESS_COOKIE_NAME }
            ?.value
    }

    private fun isUnauthenticatedEndpoint(path: String): Boolean =
        path in unauthenticatedPaths || unauthenticatedPathPrefixes.any(path::startsWith)

    private companion object {
        val unauthenticatedPaths = setOf("/actuator/health", "/auth/csrf", "/auth/refresh", "/auth/logout")
        val unauthenticatedPathPrefixes = setOf("/oauth2/", "/login/")
    }
}
