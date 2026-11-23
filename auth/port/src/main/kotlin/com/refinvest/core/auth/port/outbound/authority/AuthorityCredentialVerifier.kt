package com.refinvest.core.auth.port.outbound.authority

fun interface AuthorityCredentialVerifier {
    fun accepts(credential: String): Boolean
}
