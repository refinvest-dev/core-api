package com.refinvest.core.auth.port.inbound.auth.me

fun interface GetCurrentMemberUseCase {
    fun execute(): GetCurrentMemberResult?
}
