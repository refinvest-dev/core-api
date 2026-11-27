package com.refinvest.core.config.ownerquery

import com.refinvest.core.auth.adapter.security.authority.NimbusAuthorityDelegationVerifier
import com.refinvest.core.auth.adapter.security.authority.OpaqueAuthorityCredentialVerifier
import com.refinvest.core.auth.adapter.security.delegation.NimbusOwnerQueryDelegationSigner
import com.refinvest.core.auth.adapter.security.delegation.OwnerQuerySigningKey
import com.refinvest.core.tradingreview.adapter.reviewapi.OwnerMetadataHttpOptions
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import java.nio.ByteBuffer
import java.net.URI
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64

internal class OwnerQuerySnapshot private constructor(
    val signer: NimbusOwnerQueryDelegationSigner,
    val verifier: NimbusAuthorityDelegationVerifier,
    val authorityCaller: OpaqueAuthorityCredentialVerifier,
    val clientOptions: OwnerMetadataHttpOptions,
    val requestTimeout: Duration,
    val queryTimeoutSeconds: Int,
) {
    override fun toString(): String = "OwnerQuerySnapshot(redacted)"

    companion object {
        private val json = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(65536)
                .maxNameLength(64).maxTokenCount(512).build()).build()
        private val fields = setOf("issuer", "signingKid", "privateKey", "keys", "goOrigin", "goCredential", "goRootCAs",
            "authorityCredentials", "requestTimeout", "connectTimeout", "shutdownTimeout", "queryTimeoutSeconds", "maxInFlight")

        fun decode(bytes: ByteArray, budget: PreparationBudget): OwnerQuerySnapshot {
            try {
                budget.check()
                require(bytes.size in 1..OwnerQuerySnapshotFile.MAX_BYTES)
                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                val values = json.createParser(text).use { parser ->
                    require(parser.nextToken() == JsonToken.START_OBJECT)
                    val result = linkedMapOf<String, Any>()
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        require(parser.currentToken() == JsonToken.PROPERTY_NAME)
                        val name = parser.currentName()
                        require(name in fields && name !in result)
                        val token = parser.nextToken()
                        result[name] = when (name) {
                            "keys" -> {
                                require(token == JsonToken.START_ARRAY)
                                val keys = mutableListOf<Map<String, String>>()
                                while (parser.nextToken() != JsonToken.END_ARRAY) {
                                    require(keys.size < 32 && parser.currentToken() == JsonToken.START_OBJECT)
                                    val key = linkedMapOf<String, String>()
                                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                                        require(parser.currentToken() == JsonToken.PROPERTY_NAME)
                                        val field = parser.currentName()
                                        require(field in setOf("issuer", "kid", "publicKey") && field !in key)
                                        require(parser.nextToken() == JsonToken.VALUE_STRING)
                                        key[field] = scalar(parser.getString())
                                    }
                                    require(key.keys == setOf("issuer", "kid", "publicKey"))
                                    keys.add(key)
                                }
                                require(keys.isNotEmpty())
                                keys
                            }
                            "authorityCredentials" -> {
                                require(token == JsonToken.START_ARRAY)
                                val credentials = mutableListOf<String>()
                                while (parser.nextToken() != JsonToken.END_ARRAY) {
                                    require(credentials.size < 2 && parser.currentToken() == JsonToken.VALUE_STRING)
                                    credentials.add(scalar(parser.getString()))
                                }
                                require(credentials.isNotEmpty())
                                credentials
                            }
                            "queryTimeoutSeconds", "maxInFlight" -> {
                                require(token == JsonToken.VALUE_NUMBER_INT)
                                parser.getIntValue()
                            }
                            else -> {
                                require(token == JsonToken.VALUE_STRING)
                                scalar(parser.getString())
                            }
                        }
                    }
                    require(result.keys == fields && parser.nextToken() == null)
                    result
                }
                budget.check()
                return prepare(values, budget)
            } catch (error: OwnerQueryPreparationException) {
                throw OwnerQueryPreparationException(error.stage)
            } catch (_: Exception) {
                throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.SNAPSHOT)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun prepare(values: Map<String, Any>, budget: PreparationBudget): OwnerQuerySnapshot {
            fun text(name: String) = values.getValue(name) as String
            fun duration(name: String): Duration = Duration.parse(text(name)).also {
                require(it > Duration.ZERO && it <= Duration.ofSeconds(60))
            }
            val factory = KeyFactory.getInstance("RSA")
            val publicKeys = linkedMapOf<Pair<String, String>, RSAPublicKey>()
            for (value in values.getValue("keys") as List<Map<String, String>>) {
                budget.check()
                val identity = value.getValue("issuer") to value.getValue("kid")
                require(identity !in publicKeys)
                val encoded = pem(value.getValue("publicKey"), "PUBLIC KEY")
                val parsed = factory.generatePublic(X509EncodedKeySpec(encoded)) as RSAPublicKey
                require(encoded.contentEquals(parsed.encoded))
                publicKeys[identity] = parsed
            }
            val publicKey = requireNotNull(publicKeys[text("issuer") to text("signingKid")])
            val privateBytes = pem(text("privateKey"), "PRIVATE KEY")
            val privateKey = try {
                (factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)) as RSAPrivateKey).also {
                    require(privateBytes.contentEquals(it.encoded))
                }
            } finally {
                privateBytes.fill(0)
            }
            val key = OwnerQuerySigningKey.prepare(text("signingKid"), publicKey, privateKey)
            budget.check()

            val credentials = values.getValue("authorityCredentials") as List<String>
            val credential = text("goCredential")
            require(Regex("[A-Za-z0-9_-]{43,128}").matches(credential) && credential !in credentials)
            val certificates = certificates(text("goRootCAs"))
            val requestTimeout = duration("requestTimeout")
            val connectTimeout = duration("connectTimeout")
            require(connectTimeout <= requestTimeout)
            val queryTimeout = values.getValue("queryTimeoutSeconds") as Int
            require(queryTimeout > 0 && Duration.ofSeconds(queryTimeout.toLong()) <= requestTimeout)
            val max = values.getValue("maxInFlight") as Int
            require(max in 1..256)
            val origin = URI(text("goOrigin"))
            require(origin.scheme == "https" && !origin.host.isNullOrBlank() && origin.rawUserInfo == null &&
                origin.rawQuery == null && origin.rawFragment == null && origin.rawPath in listOf("", "/") &&
                (origin.port == -1 || origin.port in 1..65535))
            val options = OwnerMetadataHttpOptions(origin, { credential }, connectTimeout, duration("shutdownTimeout"), max,
                certificates, credentials.toSet())
            val snapshot = OwnerQuerySnapshot(NimbusOwnerQueryDelegationSigner(text("issuer")) { key },
                NimbusAuthorityDelegationVerifier(publicKeys), OpaqueAuthorityCredentialVerifier(credentials),
                options, requestTimeout, queryTimeout)
            budget.check()
            return snapshot
        }

        private fun scalar(value: String): String {
            require(value.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(value))
            return value
        }

        private fun pem(value: String, label: String): ByteArray {
            val start = "-----BEGIN $label-----"
            val end = "-----END $label-----"
            val text = value.trim('\r', '\n')
            require(text.startsWith(start) && text.endsWith(end))
            val body = text.substring(start.length, text.length - end.length)
            require(body.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=\r\n" })
            val compact = body.replace("\r", "").replace("\n", "")
            return Base64.getDecoder().decode(compact).also { require(Base64.getEncoder().encodeToString(it) == compact) }
        }

        private fun certificates(value: String): List<X509Certificate> {
            val block = Regex("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----")
            val matches = block.findAll(value).toList()
            require(matches.size in 1..32 && block.replace(value, "").isBlank())
            val factory = CertificateFactory.getInstance("X.509")
            val certificates = matches.map {
                val encoded = pem(it.value, "CERTIFICATE")
                val input = encoded.inputStream()
                (factory.generateCertificate(input) as X509Certificate).also { cert ->
                    require(input.available() == 0 && cert.basicConstraints >= 0)
                }
            }
            require(certificates.map { Base64.getEncoder().encodeToString(it.encoded) }.distinct().size == certificates.size)
            return certificates
        }
    }
}
