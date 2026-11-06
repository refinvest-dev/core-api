package com.refinvest.core.auth.port.outbound.persistence

import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.domain.valueobject.SocialIdentityId

interface SocialIdentityStore {
    fun findById(id: SocialIdentityId): SocialIdentity?
    fun save(identity: SocialIdentity)
}
