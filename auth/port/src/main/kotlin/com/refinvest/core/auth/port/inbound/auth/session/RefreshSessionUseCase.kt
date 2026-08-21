package com.refinvest.core.auth.port.inbound.auth.session

fun interface RefreshSessionUseCase {
    fun execute(command: RefreshSessionCommand): RefreshSessionResult?
}
