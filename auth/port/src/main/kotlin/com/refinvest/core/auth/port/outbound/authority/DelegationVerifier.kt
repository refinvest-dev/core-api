package com.refinvest.core.auth.port.outbound.authority

import java.time.Instant

fun interface DelegationVerifier {
    fun verify(compact: String, expectedActor: String, now: Instant): VerifiedAuthorityDelegation?
}

// A consuming contract, not proof by itself. Application always invokes its trusted verifier.
class VerifiedAuthorityDelegation(
    val subject: Long,
    val actor: String,
    val issuer: String,
    val keyId: String,
    val audience: String,
    val scope: String,
    val tokenId: String,
    val issuedAt: Long,
    val notBefore: Long,
    val expiresAt: Long,
) {
    override fun toString(): String = "VerifiedAuthorityDelegation(redacted)"
}
