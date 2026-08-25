package com.refinvest.core.auth.adapter.security.jwt

import com.refinvest.core.auth.port.outbound.token.RefreshTokenClaims
import com.refinvest.core.auth.port.outbound.token.RefreshTokenParser
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JwtRefreshTokenParser(
    private val jwtDecoder: JwtDecoder,
) : RefreshTokenParser {
    override fun parse(rawToken: String): RefreshTokenClaims? = runCatching {
        val jwt = jwtDecoder.decode(rawToken)
        require(jwt.getClaimAsString("typ") == JwtAuthenticationTokenIssuer.REFRESH_TOKEN_TYPE)
        RefreshTokenClaims(
            jti = UUID.fromString(requireNotNull(jwt.id)),
            memberId = MemberId(requireNotNull(jwt.subject).toLong()),
            familyId = UUID.fromString(requireNotNull(jwt.getClaimAsString("familyId"))),
            tokenFingerprint = RefreshTokenFingerprint.of(rawToken),
        )
    }.getOrNull()
}
