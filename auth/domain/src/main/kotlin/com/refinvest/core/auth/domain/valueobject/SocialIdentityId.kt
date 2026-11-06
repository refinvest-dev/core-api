package com.refinvest.core.auth.domain.valueobject

import com.refinvest.core.common.domain.Identifier

data class SocialIdentityId(val provider: SocialProvider, val providerSubject: String) : Identifier<String> {
    init { require(providerSubject.isNotBlank()) { "providerSubject must not be blank" } }
    override val value: String get() = "${provider.name}:$providerSubject"
}
