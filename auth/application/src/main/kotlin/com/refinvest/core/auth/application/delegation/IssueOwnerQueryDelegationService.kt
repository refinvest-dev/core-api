package com.refinvest.core.auth.application.delegation

import com.refinvest.core.auth.port.inbound.delegation.IssueOwnerQueryDelegationUseCase
import com.refinvest.core.auth.port.inbound.delegation.OwnerQueryDelegationResult
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationPayload
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationSigner
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

// Explicit preparation only: the future proxy composition supplies the signer and authenticated principal.
class IssueOwnerQueryDelegationService(
    private val currentMemberIdProvider: CurrentMemberIdProvider,
    private val signer: OwnerQueryDelegationSigner,
    private val clock: Clock,
    private val newTokenId: () -> String = { UUID.randomUUID().toString() },
) : IssueOwnerQueryDelegationUseCase {
    override fun execute(): OwnerQueryDelegationResult {
        val memberId = currentMemberIdProvider.currentMemberId()
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val payload = OwnerQueryDelegationPayload(memberId, newTokenId(), issuedAt)

        val compactToken = signer.sign(payload)

        return OwnerQueryDelegationResult(
            compactToken,
            issuedAt,
            issuedAt.plusSeconds(OwnerQueryDelegationPayload.TTL_SECONDS),
        )
    }
}
