package com.refinvest.core.tradingreview.adapter.security

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.port.TradingMemberProvider
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

@Component
class TradingSecurityMemberProvider(private val getMember: GetMemberUseCase) : TradingMemberProvider {
    override fun currentMemberId(): MemberId {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw AccessDeniedException("Authenticated member is required")
        val id = authentication.token.subject?.toLongOrNull()?.let(::MemberId)
            ?: throw AccessDeniedException("Access token subject is invalid")
        if (getMember.execute(GetMemberQuery(id)) == null) {
            throw AuthenticationCredentialsNotFoundException("Authenticated member no longer exists")
        }
        return id
    }
}
