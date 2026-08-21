package com.refinvest.core.auth.port.inbound.auth.login

import com.refinvest.core.auth.domain.SocialProvider

data class SocialLoginCommand(
    val provider: SocialProvider,
    val providerSubject: String,
)
