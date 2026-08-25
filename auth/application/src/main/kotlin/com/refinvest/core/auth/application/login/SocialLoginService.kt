package com.refinvest.core.auth.application.login

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.port.inbound.login.SocialLoginCommand
import com.refinvest.core.auth.port.inbound.login.SocialLoginResult
import com.refinvest.core.auth.port.inbound.login.SocialLoginUseCase
import com.refinvest.core.auth.port.outbound.token.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.auth.port.outbound.persistence.SocialIdentityStore
import com.refinvest.core.member.domain.MemberRole
import com.refinvest.core.member.port.inbound.create.CreateMemberCommand
import com.refinvest.core.member.port.inbound.create.CreateMemberUseCase
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class SocialLoginService(
    private val socialIdentityStore: SocialIdentityStore,
    private val refreshSessionStore: RefreshSessionStore,
    private val authenticationTokenIssuer: AuthenticationTokenIssuer,
    private val createMemberUseCase: CreateMemberUseCase,
    private val getMemberUseCase: GetMemberUseCase,
) : SocialLoginUseCase {
    @Transactional
    override fun execute(command: SocialLoginCommand): SocialLoginResult {
        val member = resolveMember(command)
        val tokens = authenticationTokenIssuer.issue(member.id, member.role.name, null)
        refreshSessionStore.save(
            RefreshSession(
                jti = tokens.refreshJti,
                memberId = member.id,
                familyId = tokens.refreshFamilyId,
                tokenFingerprint = tokens.refreshTokenFingerprint,
                issuedAt = tokens.issuedAt,
                expiresAt = tokens.refreshTokenExpiresAt,
            ),
        )

        return SocialLoginResult(
            memberId = member.id,
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
        )
    }

    private fun resolveMember(command: SocialLoginCommand): AuthenticatedMember {
        val memberId = socialIdentityStore.findMemberId(command.provider, command.providerSubject)
            ?: return createMember(command)
        val member = requireNotNull(getMemberUseCase.execute(GetMemberQuery(memberId))) {
            "Social identity refers to an unknown member"
        }
        return AuthenticatedMember(member.memberId, member.role)
    }

    private fun createMember(command: SocialLoginCommand): AuthenticatedMember {
        val member = createMemberUseCase.execute(CreateMemberCommand)
        socialIdentityStore.save(SocialIdentity(command.provider, command.providerSubject, member.memberId))
        return AuthenticatedMember(member.memberId, member.role)
    }

    private data class AuthenticatedMember(
        val id: MemberId,
        val role: MemberRole,
    )
}
