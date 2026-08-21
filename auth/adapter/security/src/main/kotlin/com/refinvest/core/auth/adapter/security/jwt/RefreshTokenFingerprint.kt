package com.refinvest.core.auth.adapter.security.jwt

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object RefreshTokenFingerprint {
    fun of(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
