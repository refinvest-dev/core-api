package com.refinvest.core.auth.domain

import com.refinvest.core.shared.kernel.member.MemberId

data class SocialIdentity(
    val provider: SocialProvider,
    val providerSubject: String,
    val memberId: MemberId,
) {
    init {
        require(providerSubject.isNotBlank()) { "providerSubject must not be blank" }
    }
}
