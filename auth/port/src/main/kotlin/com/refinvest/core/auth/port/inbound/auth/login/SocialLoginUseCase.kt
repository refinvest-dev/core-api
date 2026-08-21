package com.refinvest.core.auth.port.inbound.auth.login

fun interface SocialLoginUseCase {
    fun execute(command: SocialLoginCommand): SocialLoginResult
}
