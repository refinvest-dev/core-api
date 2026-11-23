package com.refinvest.core.auth.port.outbound.authority

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

// Trusted control consumers must supply one actual DB transaction and their own authorization/audit boundary.
interface MemberAuthorityControlStore {
    fun registerMember(memberId: MemberId): Boolean
    fun setMemberAllowed(memberId: MemberId, expectedRevision: Long, allowed: Boolean): Boolean
    fun registerCaller(callerId: String, ingress: Boolean, authority: Boolean): Boolean
    fun setCallerEnabled(callerId: String, expectedRevision: Long, enabled: Boolean): Boolean
    fun registerKey(issuer: String, keyId: String): Boolean
    fun setKeyEnabled(issuer: String, keyId: String, expectedRevision: Long, enabled: Boolean): Boolean
    fun revokeToken(issuer: String, tokenIdSha256: String, retainUntil: Instant)
}
