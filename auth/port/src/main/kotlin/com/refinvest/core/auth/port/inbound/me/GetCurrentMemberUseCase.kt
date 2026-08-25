package com.refinvest.core.auth.port.inbound.me

fun interface GetCurrentMemberUseCase {
    fun execute(): GetCurrentMemberResult?
}
