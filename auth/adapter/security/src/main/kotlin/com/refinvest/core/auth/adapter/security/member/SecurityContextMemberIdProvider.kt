package com.refinvest.core.auth.adapter.security.member

import com.refinvest.core.auth.port.outbound.CurrentMemberIdProvider
import com.refinvest.core.backtest.port.outbound.BacktestMemberIdProvider
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.outbound.MemberIdProvider
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

@Component
class SecurityContextMemberIdProvider : CurrentMemberIdProvider, MemberIdProvider, BacktestMemberIdProvider {
    override fun currentMemberId(): MemberId {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw AccessDeniedException("Authenticated member is required")
        return authentication.token.subject?.toLongOrNull()
            ?.let(::MemberId)
            ?: throw AccessDeniedException("Access token subject is invalid")
    }
}
