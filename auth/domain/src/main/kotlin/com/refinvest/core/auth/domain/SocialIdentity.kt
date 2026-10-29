package com.refinvest.core.auth.domain

import com.refinvest.core.auth.domain.valueobject.SocialIdentityId
import com.refinvest.core.auth.domain.valueobject.SocialProvider
import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId

class SocialIdentity private constructor(id: SocialIdentityId, val memberId: MemberId) : AggregateRoot<SocialIdentityId>(id) {
    val provider: SocialProvider get() = id.provider
    val providerSubject: String get() = id.providerSubject

    companion object {
        fun create(id: SocialIdentityId, memberId: MemberId): SocialIdentity = SocialIdentity(id, memberId)
        fun restore(id: SocialIdentityId, memberId: MemberId): SocialIdentity = SocialIdentity(id, memberId)
    }
}
