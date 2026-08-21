package com.refinvest.core.auth.port.inbound.auth.logout

fun interface LogoutUseCase {
    fun execute(command: LogoutCommand)
}
