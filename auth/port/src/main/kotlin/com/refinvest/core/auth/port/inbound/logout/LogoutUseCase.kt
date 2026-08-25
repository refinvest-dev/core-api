package com.refinvest.core.auth.port.inbound.logout

fun interface LogoutUseCase {
    fun execute(command: LogoutCommand)
}
