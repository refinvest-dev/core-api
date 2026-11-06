package com.refinvest.core.auth.port.inbound.logout

class LogoutCommand(val rawRefreshToken: String?) {
    override fun toString(): String = "LogoutCommand(redacted)"
}
