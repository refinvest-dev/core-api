package com.refinvest.core.auth.adapter.security.jwt

import com.refinvest.core.auth.adapter.security.config.RefInvestSecurityProperties
import com.refinvest.core.auth.port.outbound.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.IssuedAuthenticationTokens
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Component
class JwtAuthenticationTokenIssuer(
    private val jwtEncoder: JwtEncoder,
    private val properties: RefInvestSecurityProperties,
    private val clock: Clock,
) : AuthenticationTokenIssuer {
    override fun issue(memberId: MemberId, role: String, refreshFamilyId: UUID?): IssuedAuthenticationTokens {
        val issuedAt = clock.instant()
        val accessExpiresAt = issuedAt.plus(Duration.ofMinutes(properties.accessTokenMinutes))
        val refreshExpiresAt = issuedAt.plus(Duration.ofDays(properties.refreshTokenDays))
        val refreshJti = UUID.randomUUID()
        val familyId = refreshFamilyId ?: UUID.randomUUID()
        val accessToken = encode(
            memberId = memberId,
            role = role,
            jti = UUID.randomUUID(),
            issuedAt = issuedAt,
            expiresAt = accessExpiresAt,
            tokenType = ACCESS_TOKEN_TYPE,
            familyId = null,
        )
        val refreshToken = encode(
            memberId = memberId,
            role = role,
            jti = refreshJti,
            issuedAt = issuedAt,
            expiresAt = refreshExpiresAt,
            tokenType = REFRESH_TOKEN_TYPE,
            familyId = familyId,
        )
        return IssuedAuthenticationTokens(
            issuedAt = issuedAt,
            accessToken = accessToken,
            accessTokenExpiresAt = accessExpiresAt,
            refreshToken = refreshToken,
            refreshJti = refreshJti,
            refreshFamilyId = familyId,
            refreshTokenFingerprint = RefreshTokenFingerprint.of(refreshToken),
            refreshTokenExpiresAt = refreshExpiresAt,
        )
    }

    private fun encode(
        memberId: MemberId,
        role: String,
        jti: UUID,
        issuedAt: java.time.Instant,
        expiresAt: java.time.Instant,
        tokenType: String,
        familyId: UUID?,
    ): String = jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer(properties.issuer)
                    .audience(listOf(properties.audience))
                    .subject(memberId.value.toString())
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .id(jti.toString())
                    .claim("role", role)
                    .claim("typ", tokenType)
                    .apply { familyId?.let { claim("familyId", it.toString()) } }
                    .build(),
            ),
    ).tokenValue

    companion object {
        const val ACCESS_TOKEN_TYPE = "access"
        const val REFRESH_TOKEN_TYPE = "refresh"
    }
}
