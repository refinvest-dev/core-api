package com.refinvest.core.auth.adapter.web.config

import com.refinvest.core.auth.adapter.web.jwt.AccessCookieBearerTokenResolver
import com.refinvest.core.auth.adapter.web.error.JsonAccessDeniedHandler
import com.refinvest.core.auth.adapter.web.error.JsonAuthenticationEntryPoint
import com.refinvest.core.auth.adapter.web.oauth.ReturnToAuthorizationRequestResolver
import com.refinvest.core.auth.adapter.web.oauth.ReturnToValidationFilter
import com.refinvest.core.auth.adapter.web.oauth.SocialLoginFailureHandler
import com.refinvest.core.auth.adapter.web.oauth.SocialLoginSuccessHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableConfigurationProperties(AuthWebProperties::class)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        properties: AuthWebProperties,
        socialLoginSuccessHandler: SocialLoginSuccessHandler,
        socialLoginFailureHandler: SocialLoginFailureHandler,
        returnToAuthorizationRequestResolver: ReturnToAuthorizationRequestResolver,
        returnToValidationFilter: ReturnToValidationFilter,
        clientRegistrationRepositoryProvider: ObjectProvider<ClientRegistrationRepository>,
        jsonAuthenticationEntryPoint: JsonAuthenticationEntryPoint,
        jsonAccessDeniedHandler: JsonAccessDeniedHandler,
        @Qualifier("accessTokenJwtDecoder") accessTokenDecoder: JwtDecoder,
    ): SecurityFilterChain {
        http.cors { it.configurationSource(corsConfigurationSource(properties)) }
        if (clientRegistrationRepositoryProvider.getIfAvailable() != null) {
            http.oauth2Login {
                it.authorizationEndpoint { endpoint -> endpoint.authorizationRequestResolver(returnToAuthorizationRequestResolver) }
                it.successHandler(socialLoginSuccessHandler)
                    .failureHandler(socialLoginFailureHandler)
            }
            http.addFilterBefore(returnToValidationFilter, OAuth2AuthorizationRequestRedirectFilter::class.java)
        }
        http.csrf {
            it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(RequestMatcher { request ->
                    request.method !in setOf("GET", "HEAD", "TRACE", "OPTIONS")
                })
        }
        http.authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/actuator/health", "/oauth2/**", "/login/**", "/auth/csrf", "/assets/series",
                    "/internal/trading-review/artifact-grants/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/refresh", "/auth/logout").permitAll()
                .anyRequest().authenticated()
        }
        http.exceptionHandling {
            it.authenticationEntryPoint(jsonAuthenticationEntryPoint)
                .accessDeniedHandler(jsonAccessDeniedHandler)
        }
        // The Resource Server DSL exempts all bearer-token requests from CSRF by design. That is appropriate for
        // Authorization headers, but not for our browser cookies. Keep Spring Security's standard JWT provider and
        // bearer filter while retaining CSRF protection for every unsafe cookie-authenticated request.
        http.addFilterBefore(
            accessTokenAuthenticationFilter(accessTokenDecoder, jsonAuthenticationEntryPoint),
            CsrfFilter::class.java,
        )
        return http.build()
    }

    private fun accessTokenAuthenticationFilter(
        jwtDecoder: JwtDecoder,
        authenticationEntryPoint: JsonAuthenticationEntryPoint,
    ): BearerTokenAuthenticationFilter {
        val authenticationProvider = JwtAuthenticationProvider(jwtDecoder).apply {
            setJwtAuthenticationConverter(JwtAuthenticationConverter().apply {
                setJwtGrantedAuthoritiesConverter { jwt ->
                    jwt.getClaimAsString("role")?.let { listOf(SimpleGrantedAuthority("ROLE_$it")) }.orEmpty()
                }
            })
        }
        val cookieTokenResolver = AccessCookieBearerTokenResolver()
        val authenticationConverter = AuthenticationConverter { request ->
            cookieTokenResolver.resolve(request)?.let(::BearerTokenAuthenticationToken)
        }
        return BearerTokenAuthenticationFilter(
            AuthenticationManager(authenticationProvider::authenticate),
            authenticationConverter,
        ).apply {
            setAuthenticationFailureHandler(AuthenticationEntryPointFailureHandler(authenticationEntryPoint))
        }
    }

    private fun corsConfigurationSource(properties: AuthWebProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = listOf(properties.webOrigin)
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().also { source -> source.registerCorsConfiguration("/**", configuration) }
    }

}
