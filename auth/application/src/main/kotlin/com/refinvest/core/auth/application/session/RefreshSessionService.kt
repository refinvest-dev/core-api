package com.refinvest.core.auth.application.session

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.port.inbound.session.RefreshSessionCommand
import com.refinvest.core.auth.port.inbound.session.RefreshSessionResult
import com.refinvest.core.auth.port.inbound.session.RefreshSessionUseCase
import com.refinvest.core.auth.port.outbound.token.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
open class RefreshSessionService(
    private val refreshSessionStore: RefreshSessionStore,
    private val getMemberUseCase: GetMemberUseCase,
    private val authenticationTokenIssuer: AuthenticationTokenIssuer,
    private val clock: Clock,
) : RefreshSessionUseCase {
    @Transactional
    override fun execute(command: RefreshSessionCommand): RefreshSessionResult? {
        val now = clock.instant()
        val current = refreshSessionStore.findByJti(command.jti)
        if (current == null || current.isInvalidFor(command, now)) return revokeAndReject(command.familyId, now)

        val member = getMemberUseCase.execute(GetMemberQuery(current.memberId))
        if (member == null) {
            return revokeAndReject(current.familyId, now)
        }

        val tokens = authenticationTokenIssuer.issue(member.memberId, member.role.name, current.familyId)
        val replacement = RefreshSession(
            jti = tokens.refreshJti,
            memberId = member.memberId,
            familyId = tokens.refreshFamilyId,
            tokenFingerprint = tokens.refreshTokenFingerprint,
            issuedAt = tokens.issuedAt,
            expiresAt = tokens.refreshTokenExpiresAt,
        )
        if (!refreshSessionStore.rotate(current, replacement, now)) {
            return revokeAndReject(current.familyId, now)
        }

        return RefreshSessionResult(
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
        )
    }

    private fun RefreshSession.isInvalidFor(command: RefreshSessionCommand, now: Instant): Boolean =
        memberId != command.memberId ||
            familyId != command.familyId ||
            tokenFingerprint != command.tokenFingerprint ||
            !isActive(now)

    private fun revokeAndReject(familyId: UUID, revokedAt: Instant): RefreshSessionResult? {
        refreshSessionStore.revokeFamily(familyId, revokedAt)
        return null
    }
}
