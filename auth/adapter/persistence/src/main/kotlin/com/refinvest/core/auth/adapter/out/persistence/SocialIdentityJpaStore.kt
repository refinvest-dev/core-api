package com.refinvest.core.auth.adapter.out.persistence

import org.springframework.data.repository.CrudRepository

interface SocialIdentityJpaStore : CrudRepository<SocialIdentityJpaEntity, Long> {
    fun findByProviderAndProviderSubject(provider: String, providerSubject: String): SocialIdentityJpaEntity?
}
