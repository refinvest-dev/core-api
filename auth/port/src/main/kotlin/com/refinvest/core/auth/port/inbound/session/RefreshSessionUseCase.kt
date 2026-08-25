package com.refinvest.core.auth.port.inbound.session

fun interface RefreshSessionUseCase {
    fun execute(command: RefreshSessionCommand): RefreshSessionResult?
}
