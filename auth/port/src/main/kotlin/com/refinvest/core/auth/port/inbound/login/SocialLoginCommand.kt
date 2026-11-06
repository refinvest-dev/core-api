package com.refinvest.core.auth.port.inbound.login

import com.refinvest.core.auth.domain.valueobject.SocialProvider

data class SocialLoginCommand(
    val provider: SocialProvider,
    val providerSubject: String,
)
