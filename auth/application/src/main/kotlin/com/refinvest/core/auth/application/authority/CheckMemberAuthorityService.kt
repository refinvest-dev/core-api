package com.refinvest.core.auth.application.authority

import com.refinvest.core.auth.port.inbound.authority.ActiveAuthorityIdentity
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityCommand
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import com.refinvest.core.auth.port.inbound.authority.MemberAuthorityResult
import com.refinvest.core.auth.port.inbound.authority.MemberAuthorityUnavailableException
import com.refinvest.core.auth.port.outbound.authority.DelegationVerifier
import com.refinvest.core.auth.port.outbound.authority.MemberAuthorityLookup
import com.refinvest.core.auth.port.outbound.authority.MemberAuthorityReader
import java.security.MessageDigest
import java.time.Clock

class CheckMemberAuthorityService(
    private val verifier: DelegationVerifier,
    private val reader: MemberAuthorityReader,
    private val clock: Clock,
) : CheckMemberAuthorityUseCase {
    override fun execute(command: CheckMemberAuthorityCommand): MemberAuthorityResult {
        try {
            val hash = sha256(command.delegation)
            val now = clock.instant()
            val verified = verifier.verify(command.delegation, command.expectedActor, now)
                ?: return MemberAuthorityResult(command.checkId, hash, now, null)

            val snapshot = reader.check(MemberAuthorityLookup(verified, sha256(verified.tokenId)))
            val identity = if (snapshot.active) {
                val revision = snapshot.policyRevision?.takeIf { it > 0 } ?: throw MemberAuthorityUnavailableException()
                ActiveAuthorityIdentity(verified.subject.toString(), verified.actor, verified.issuer, verified.audience,
                    verified.scope, verified.tokenId, verified.expiresAt, revision)
            } else null

            return MemberAuthorityResult(command.checkId, hash, snapshot.checkedAt, identity)
        } catch (_: Exception) {
            throw MemberAuthorityUnavailableException()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
