package com.refinvest.core.auth.port.inbound.login

fun interface SocialLoginUseCase {
    fun execute(command: SocialLoginCommand): SocialLoginResult
}
