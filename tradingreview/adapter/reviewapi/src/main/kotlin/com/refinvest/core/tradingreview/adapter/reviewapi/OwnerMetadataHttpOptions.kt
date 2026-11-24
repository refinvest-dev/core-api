package com.refinvest.core.tradingreview.adapter.reviewapi

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class OwnerMetadataHttpOptions(
    val origin: URI,
    val credential: () -> String,
    val connectTimeout: Duration,
    val shutdownTimeout: Duration,
    val maxInFlight: Int,
    val rootCertificates: List<X509Certificate>? = null,
    val forbiddenCredentials: Set<String> = emptySet(),
) {
    internal fun validate() {
        require(origin.scheme == "https" && !origin.host.isNullOrBlank() && origin.rawUserInfo == null &&
            origin.rawQuery == null && origin.rawFragment == null && origin.rawPath in listOf("", "/") &&
            (origin.port == -1 || origin.port in 1..65535) && maxInFlight in 1..256 &&
            connectTimeout > Duration.ZERO && connectTimeout <= Duration.ofSeconds(60) &&
            shutdownTimeout > Duration.ZERO && shutdownTimeout <= Duration.ofSeconds(60) &&
            (rootCertificates == null || rootCertificates.size in 1..32) && forbiddenCredentials.size <= 128) {
            "Invalid owner metadata client settings"
        }
    }

    internal fun trustContext(): SSLContext {
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        val store = rootCertificates?.let { certificates ->
            val factory = CertificateFactory.getInstance("X.509")
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { index, certificate ->
                    setCertificateEntry("ca-$index", factory.generateCertificate(ByteArrayInputStream(certificate.encoded)))
                }
            }
        }
        managers.init(store)
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }

    override fun toString(): String = "OwnerMetadataHttpOptions(redacted)"
}
