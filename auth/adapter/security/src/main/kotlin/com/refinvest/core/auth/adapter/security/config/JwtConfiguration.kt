package com.refinvest.core.auth.adapter.security.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import com.refinvest.core.auth.adapter.security.jwt.JwtAuthenticationTokenIssuer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableConfigurationProperties(RefInvestSecurityProperties::class)
class JwtConfiguration {
    @Bean
    fun jwtEncoder(properties: RefInvestSecurityProperties): JwtEncoder = NimbusJwtEncoder(
        ImmutableSecret<SecurityContext>(secretKey(properties)),
    )

    @Bean
    @Primary
    fun jwtDecoder(properties: RefInvestSecurityProperties): JwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
        .build().also { decoder ->
            decoder.setJwtValidator(DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.issuer),
                OAuth2TokenValidator<Jwt> { jwt ->
                    if (properties.audience in jwt.audience.orEmpty()) OAuth2TokenValidatorResult.success()
                    else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
                },
            ))
        }

    @Bean
    fun accessTokenJwtDecoder(@Qualifier("jwtDecoder") jwtDecoder: JwtDecoder): JwtDecoder =
        JwtDecoder { token ->
            jwtDecoder.decode(token).also { jwt ->
                if (jwt.getClaimAsString("typ") != JwtAuthenticationTokenIssuer.ACCESS_TOKEN_TYPE) {
                    throw BadJwtException("JWT is not an access token")
                }
            }
        }

    private fun secretKey(properties: RefInvestSecurityProperties) = SecretKeySpec(properties.jwtSecret.toByteArray(), "HmacSHA256")
}
