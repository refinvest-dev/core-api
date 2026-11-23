package com.refinvest.core.auth.port.outbound.authority

import java.time.Instant

fun interface MemberAuthorityReader {
    fun check(lookup: MemberAuthorityLookup): MemberAuthoritySnapshot
}

class MemberAuthorityLookup(val delegation: VerifiedAuthorityDelegation, val tokenIdSha256: String) {
    override fun toString(): String = "MemberAuthorityLookup(redacted)"
}

class MemberAuthoritySnapshot(val active: Boolean, val checkedAt: Instant, val policyRevision: Long?)

class AuthorityStateUnavailableException : RuntimeException("Authority state unavailable")
