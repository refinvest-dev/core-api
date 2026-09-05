package com.refinvest.core.auth.adapter.security.oauth

import com.refinvest.core.auth.adapter.security.error.SecurityErrorResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
class ReturnToValidationFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "GET" || !request.requestURI.startsWith(ReturnToAuthorizationRequestResolver.AUTHORIZATION_PATH_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val returnTo = request.getParameter(ReturnToAuthorizationRequestResolver.RETURN_TO_PARAMETER)
        if (returnTo != null && !ReturnToAuthorizationRequestResolver.isSafeRelativePath(returnTo)) {
            response.status = HttpStatus.BAD_REQUEST.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            objectMapper.writeValue(response.outputStream, SecurityErrorResponse("BAD_REQUEST", "Invalid returnTo"))
            return
        }

        filterChain.doFilter(request, response)
    }
}
