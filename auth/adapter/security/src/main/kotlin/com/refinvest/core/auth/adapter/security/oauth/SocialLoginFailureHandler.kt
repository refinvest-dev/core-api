package com.refinvest.core.auth.adapter.security.oauth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.stereotype.Component

@Component
class SocialLoginFailureHandler : AuthenticationFailureHandler {
    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        logger.warn(
            "Social login failed: type={}, reason={}",
            exception::class.simpleName,
            exception.message,
        )
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Social login failed")
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SocialLoginFailureHandler::class.java)
    }
}
