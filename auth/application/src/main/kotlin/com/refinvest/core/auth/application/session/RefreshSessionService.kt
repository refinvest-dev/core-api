package com.refinvest.core.auth.application.session

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.port.inbound.session.RefreshSessionCommand
import com.refinvest.core.auth.port.inbound.session.RefreshSessionResult
import com.refinvest.core.auth.port.inbound.session.RefreshSessionUseCase
import com.refinvest.core.auth.port.outbound.token.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.token.RefreshTokenParser
import com.refinvest.core.auth.port.outbound.token.RefreshTokenClaims
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
open class RefreshSessionService(
    private val refreshSessionStore: RefreshSessionStore,
    private val getMemberUseCase: GetMemberUseCase,
    private val authenticationTokenIssuer: AuthenticationTokenIssuer,
    private val clock: Clock,
    private val refreshTokenParser: RefreshTokenParser,
    private val transactionTemplate: TransactionTemplate,
) : RefreshSessionUseCase {
    override fun execute(command: RefreshSessionCommand): RefreshSessionResult? {
        val claims = command.rawRefreshToken?.let(refreshTokenParser::parse) ?: return null
        return transactionTemplate.execute { rotate(claims) }
    }

    private fun rotate(claims: RefreshTokenClaims): RefreshSessionResult? {
        val now = clock.instant()
        val current = refreshSessionStore.findByJti(claims.jti)
        if (current == null || !current.accepts(claims.memberId, claims.familyId, claims.tokenFingerprint, now)) return revokeAndReject(claims.familyId, now)

        val member = getMemberUseCase.execute(GetMemberQuery(current.memberId))
        if (member == null) {
            return revokeAndReject(current.familyId, now)
        }

        val tokens = authenticationTokenIssuer.issue(member.memberId, member.role.name, current.familyId)
        val replacement = RefreshSession.create(
            jti = tokens.refreshJti,
            memberId = member.memberId,
            familyId = tokens.refreshFamilyId,
            tokenFingerprint = tokens.refreshTokenFingerprint,
            issuedAt = tokens.issuedAt,
            expiresAt = tokens.refreshTokenExpiresAt,
        )
        if (!refreshSessionStore.rotate(current.rotate(replacement, now))) {
            return revokeAndReject(current.familyId, now)
        }

        return RefreshSessionResult(
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
        )
    }

    private fun revokeAndReject(familyId: UUID, revokedAt: Instant): RefreshSessionResult? {
        refreshSessionStore.revokeFamily(RefreshFamilyRevocation(familyId, revokedAt))
        return null
    }
}
