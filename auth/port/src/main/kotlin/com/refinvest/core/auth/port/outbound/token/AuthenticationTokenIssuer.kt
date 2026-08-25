package com.refinvest.core.auth.port.outbound.token

import com.refinvest.core.shared.kernel.member.MemberId
import java.util.UUID

fun interface AuthenticationTokenIssuer {
    fun issue(memberId: MemberId, role: String, refreshFamilyId: UUID?): IssuedAuthenticationTokens
}
