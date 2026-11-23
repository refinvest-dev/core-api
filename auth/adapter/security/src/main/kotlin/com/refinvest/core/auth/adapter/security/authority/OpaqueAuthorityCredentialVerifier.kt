package com.refinvest.core.auth.adapter.security.authority

import com.refinvest.core.auth.port.outbound.authority.AuthorityCredentialVerifier
import java.security.MessageDigest

class OpaqueAuthorityCredentialVerifier(credentials: List<String>) : AuthorityCredentialVerifier {
    private val hashes: List<ByteArray>

    init {
        require(credentials.size in 1..2 && credentials.all { pattern.matches(it) }) { "Invalid authority credential snapshot" }
        hashes = credentials.map(::hash)
        require(hashes.map { it.toList() }.distinct().size == hashes.size) { "Invalid authority credential snapshot" }
    }

    override fun accepts(credential: String): Boolean {
        if (!pattern.matches(credential)) return false
        val candidate = hash(credential)
        var matched = false
        for (expected in hashes) matched = MessageDigest.isEqual(expected, candidate) or matched
        return matched
    }

    override fun toString(): String = "OpaqueAuthorityCredentialVerifier(redacted)"

    private fun hash(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))

    companion object {
        private val pattern = Regex("[A-Za-z0-9_-]{43,128}")
    }
}
