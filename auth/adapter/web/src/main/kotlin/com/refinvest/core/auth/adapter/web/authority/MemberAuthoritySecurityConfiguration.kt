package com.refinvest.core.auth.adapter.web.authority

import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Configuration
@ConditionalOnBean(CheckMemberAuthorityUseCase::class, AuthenticateAuthorityCallerUseCase::class)
class MemberAuthoritySecurityConfiguration {
    @Bean
    @Order(1)
    fun authoritySecurityChain(http: HttpSecurity, caller: AuthenticateAuthorityCallerUseCase, mapper: ObjectMapper): SecurityFilterChain {
        val entryPoint = org.springframework.security.web.AuthenticationEntryPoint { _, response, _ ->
            response.status = 401
            response.contentType = "application/json"
            response.setHeader("Cache-Control", "no-store")
            mapper.writeValue(response.outputStream, mapOf("code" to "AUTHORITY_SERVICE_AUTHENTICATION_FAILED",
                "message" to "Service authentication required", "traceId" to UUID.randomUUID().toString(), "retryable" to false))
        }
        val manager = AuthenticationManager { authentication ->
            val credential = (authentication as? BearerTokenAuthenticationToken)?.token ?: throw BadCredentialsException("Invalid service credential")
            if (!caller.accepts(credential)) throw BadCredentialsException("Invalid service credential")
            UsernamePasswordAuthenticationToken.authenticated(AuthenticateAuthorityCallerUseCase.CALLER_ID, null,
                listOf(SimpleGrantedAuthority("TRADING_REVIEW_AUTHORITY_CALLER")))
        }
        val converter = AuthenticationConverter { request ->
            val values = request.getHeaders("Authorization").toList()
            if (values.isEmpty()) null else {
                if (values.size != 1 || !values[0].startsWith("Bearer ", true))
                    throw OAuth2AuthenticationException(OAuth2Error("invalid_token"))
                val raw = values[0].substring(7)
                if (raw.isBlank()) throw OAuth2AuthenticationException(OAuth2Error("invalid_token"))
                BearerTokenAuthenticationToken(raw)
            }
        }
        val filter = BearerTokenAuthenticationFilter(manager, converter).apply {
            setAuthenticationEntryPoint(entryPoint)
            setAuthenticationFailureHandler(AuthenticationEntryPointFailureHandler(entryPoint))
        }

        http.securityMatcher(MemberAuthorityController.PATH)
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .authorizeHttpRequests { it.anyRequest().hasAuthority("TRADING_REVIEW_AUTHORITY_CALLER") }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
            .addFilterBefore(filter, CsrfFilter::class.java)
        http.addFilterBefore(object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                response.setHeader("Cache-Control", "no-store")
                chain.doFilter(request, response)
            }
        }, BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }
}
