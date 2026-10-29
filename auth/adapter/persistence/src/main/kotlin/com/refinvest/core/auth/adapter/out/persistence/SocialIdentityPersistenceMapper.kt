package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.domain.valueobject.SocialIdentityId
import com.refinvest.core.auth.domain.valueobject.SocialProvider
import com.refinvest.core.shared.kernel.member.MemberId

internal object SocialIdentityPersistenceMapper {
    fun toEntity(identity: SocialIdentity): SocialIdentityJpaEntity = SocialIdentityJpaEntity(
        provider = identity.provider.name, providerSubject = identity.providerSubject, memberId = identity.memberId.value)

    fun toDomain(entity: SocialIdentityJpaEntity): SocialIdentity = SocialIdentity.restore(
        SocialIdentityId(SocialProvider.valueOf(entity.provider), entity.providerSubject), MemberId(entity.memberId))
}
