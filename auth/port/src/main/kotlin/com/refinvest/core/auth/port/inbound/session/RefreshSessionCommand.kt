package com.refinvest.core.auth.port.inbound.session

class RefreshSessionCommand(val rawRefreshToken: String?) {
    override fun toString(): String = "RefreshSessionCommand(redacted)"
}
