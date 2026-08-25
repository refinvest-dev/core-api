package com.refinvest.core.auth.port.outbound.persistence

import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.domain.SocialProvider
import com.refinvest.core.shared.kernel.member.MemberId

interface SocialIdentityStore {
    fun findMemberId(provider: SocialProvider, providerSubject: String): MemberId?

    fun save(identity: SocialIdentity)
}
