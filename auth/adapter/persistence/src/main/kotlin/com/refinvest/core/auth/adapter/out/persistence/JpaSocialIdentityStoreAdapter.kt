package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.domain.SocialProvider
import com.refinvest.core.auth.port.outbound.SocialIdentityStore
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.stereotype.Repository

@Repository
class JpaSocialIdentityStoreAdapter(
    private val socialIdentityJpaStore: SocialIdentityJpaStore,
) : SocialIdentityStore {
    override fun findMemberId(provider: SocialProvider, providerSubject: String): MemberId? =
        socialIdentityJpaStore.findByProviderAndProviderSubject(provider.name, providerSubject)
            ?.let { identity -> MemberId(identity.memberId) }

    override fun save(identity: SocialIdentity) {
        socialIdentityJpaStore.save(
            SocialIdentityJpaEntity(
                provider = identity.provider.name,
                providerSubject = identity.providerSubject,
                memberId = identity.memberId.value,
            ),
        )
    }
}
