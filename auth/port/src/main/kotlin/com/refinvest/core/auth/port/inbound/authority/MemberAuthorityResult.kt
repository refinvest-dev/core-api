package com.refinvest.core.auth.port.inbound.authority

import java.time.Instant

class MemberAuthorityResult(
    val checkId: String,
    val tokenSha256: String,
    val checkedAt: Instant,
    val identity: ActiveAuthorityIdentity?,
) {
    val active: Boolean get() = identity != null

    override fun toString(): String = "MemberAuthorityResult(redacted)"
}

class ActiveAuthorityIdentity(
    val subject: String,
    val actor: String,
    val issuer: String,
    val audience: String,
    val scope: String,
    val tokenId: String,
    val expiresAt: Long,
    val policyRevision: Long,
) {
    override fun toString(): String = "ActiveAuthorityIdentity(redacted)"
}

class MemberAuthorityUnavailableException : RuntimeException("Member authority unavailable")
