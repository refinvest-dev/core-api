package com.refinvest.core.auth.adapter.security.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import com.refinvest.core.auth.adapter.security.jwt.AccessCookieBearerTokenResolver
import com.refinvest.core.auth.adapter.security.jwt.JwtAuthenticationTokenIssuer
import com.refinvest.core.auth.adapter.security.error.JsonAccessDeniedHandler
import com.refinvest.core.auth.adapter.security.error.JsonAuthenticationEntryPoint
import com.refinvest.core.auth.adapter.security.oauth.ReturnToAuthorizationRequestResolver
import com.refinvest.core.auth.adapter.security.oauth.SocialLoginFailureHandler
import com.refinvest.core.auth.adapter.security.oauth.SocialLoginSuccessHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
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
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableConfigurationProperties(RefInvestSecurityProperties::class)
class SecurityConfiguration {
    @Bean
    fun jwtEncoder(properties: RefInvestSecurityProperties): JwtEncoder = NimbusJwtEncoder(
        ImmutableSecret<SecurityContext>(secretKey(properties)),
    )

    @Bean
    fun jwtDecoder(properties: RefInvestSecurityProperties): JwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
        .build()
        .also { decoder ->
            decoder.setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefaultWithIssuer(properties.issuer),
                    audienceValidator(properties.audience),
                ),
            )
        }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        properties: RefInvestSecurityProperties,
        socialLoginSuccessHandler: SocialLoginSuccessHandler,
        socialLoginFailureHandler: SocialLoginFailureHandler,
        returnToAuthorizationRequestResolver: ReturnToAuthorizationRequestResolver,
        jsonAuthenticationEntryPoint: JsonAuthenticationEntryPoint,
        jsonAccessDeniedHandler: JsonAccessDeniedHandler,
    ): SecurityFilterChain {
        http.cors { it.configurationSource(corsConfigurationSource(properties)) }
        http.oauth2Login {
            it.authorizationEndpoint { endpoint -> endpoint.authorizationRequestResolver(returnToAuthorizationRequestResolver) }
            it.successHandler(socialLoginSuccessHandler)
                .failureHandler(socialLoginFailureHandler)
        }
        http.csrf {
            it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(RequestMatcher { request ->
                    request.method !in setOf("GET", "HEAD", "TRACE", "OPTIONS")
                })
        }
        http.authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/actuator/health", "/oauth2/**", "/login/**", "/auth/csrf").permitAll()
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
            accessTokenAuthenticationFilter(jwtDecoder(properties), jsonAuthenticationEntryPoint),
            CsrfFilter::class.java,
        )
        return http.build()
    }

    private fun audienceValidator(audience: String): OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> =
        OAuth2TokenValidator { jwt ->
            if (audience in jwt.audience.orEmpty()) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(org.springframework.security.oauth2.core.OAuth2Error("invalid_token"))
        }

    private fun jwtAuthenticationConverter(): (org.springframework.security.oauth2.jwt.Jwt) -> AbstractAuthenticationToken =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter { jwt ->
                jwt.getClaimAsString("role")
                    ?.let { role -> listOf(SimpleGrantedAuthority("ROLE_$role")) }
                    .orEmpty()
            }
        }::convert

    private fun accessTokenAuthenticationFilter(
        jwtDecoder: JwtDecoder,
        authenticationEntryPoint: JsonAuthenticationEntryPoint,
    ): BearerTokenAuthenticationFilter {
        val accessTokenDecoder = JwtDecoder { token ->
            jwtDecoder.decode(token).also { jwt ->
                if (jwt.getClaimAsString("typ") != JwtAuthenticationTokenIssuer.ACCESS_TOKEN_TYPE) {
                    throw BadJwtException("JWT is not an access token")
                }
            }
        }
        val authenticationProvider = JwtAuthenticationProvider(accessTokenDecoder).apply {
            setJwtAuthenticationConverter(jwtAuthenticationConverter())
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

    private fun corsConfigurationSource(properties: RefInvestSecurityProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = listOf(properties.webOrigin)
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "X-XSRF-TOKEN")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().also { source -> source.registerCorsConfiguration("/**", configuration) }
    }

    private fun secretKey(properties: RefInvestSecurityProperties): SecretKeySpec =
        SecretKeySpec(properties.jwtSecret.toByteArray(), "HmacSHA256")
}
