package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.domain.valueobject.SocialIdentityId
import com.refinvest.core.auth.port.outbound.persistence.SocialIdentityStore
import org.springframework.stereotype.Repository

@Repository
class JpaSocialIdentityStoreAdapter(private val socialIdentityJpaStore: SocialIdentityJpaStore) : SocialIdentityStore {
    override fun findById(id: SocialIdentityId): SocialIdentity? = socialIdentityJpaStore
        .findByProviderAndProviderSubject(id.provider.name, id.providerSubject)?.let(SocialIdentityPersistenceMapper::toDomain)

    override fun save(identity: SocialIdentity) {
        socialIdentityJpaStore.save(SocialIdentityPersistenceMapper.toEntity(identity))
    }
}
