package com.refinvest.core.auth.application.authority

import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.outbound.authority.AuthorityCredentialVerifier

class AuthenticateAuthorityCallerService(private val verifier: AuthorityCredentialVerifier) : AuthenticateAuthorityCallerUseCase {
    override fun accepts(credential: String): Boolean = try {
        verifier.accepts(credential)
    } catch (_: Exception) {
        false
    }
}
