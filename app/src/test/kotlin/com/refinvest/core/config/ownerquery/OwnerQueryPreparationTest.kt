package com.refinvest.core.config.ownerquery

import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityCommand
import com.refinvest.core.auth.port.inbound.authority.MemberAuthorityUnavailableException
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.adapter.reviewapi.OwnerMetadataHttpClient
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResource
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResult
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegation
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.mockito.Mockito.*
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerQueryPreparationTest {
    private lateinit var directory: Path
    private lateinit var signing: KeyPair
    private lateinit var certificate: X509Certificate
    private lateinit var tlsIdentity: KeyStore
    private val mapper = JsonMapper.builder().build()
    private val issuer = "https://issuer.invalid/owner-query"
    private val clock = Clock.systemUTC()
    private val member = CurrentMemberIdProvider { MemberId(9007199254740993) }
    private val primary = mock(DataSource::class.java)

    @BeforeAll
    fun inputs(@TempDir directory: Path) {
        this.directory = directory
        signing = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val store = directory.resolve("synthetic-ca.p12")
        val process = ProcessBuilder(executable.toString(), "-genkeypair", "-alias", "fixture", "-keystore", store.toString(),
            "-storetype", "PKCS12", "-storepass", "fixture-only", "-dname", "CN=synthetic-owner-query-ca", "-ext", "bc=ca:true",
            "-ext", "SAN=ip:127.0.0.1", "-validity", "2", "-keyalg", "RSA", "-keysize", "2048", "-noprompt")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Synthetic certificate generation timed out")
        }
        check(process.exitValue() == 0) { "Synthetic certificate generation failed" }
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, "fixture-only".toCharArray()) } }
        certificate = keys.getCertificate("fixture") as X509Certificate
        tlsIdentity = keys
    }

    private fun pem(label: String, bytes: ByteArray) = "-----BEGIN $label-----\n" +
        Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) + "\n-----END $label-----"

    private fun values(): MutableMap<String, Any> = linkedMapOf(
        "issuer" to issuer, "signingKid" to "synthetic-key", "privateKey" to pem("PRIVATE KEY", signing.private.encoded),
        "keys" to listOf(mapOf("issuer" to issuer, "kid" to "synthetic-key", "publicKey" to pem("PUBLIC KEY", signing.public.encoded))),
        "goOrigin" to "https://127.0.0.1:65534", "goCredential" to "A".repeat(43), "goRootCAs" to pem("CERTIFICATE", certificate.encoded),
        "authorityCredentials" to listOf("B".repeat(43), "C".repeat(43)), "requestTimeout" to "PT5S",
        "connectTimeout" to "PT1S", "shutdownTimeout" to "PT2S", "queryTimeoutSeconds" to 2, "maxInFlight" to 4,
    )

    private fun file(bytes: ByteArray = mapper.writeValueAsBytes(values())): Path =
        Files.createTempFile(directory, "snapshot-", ".json").also { Files.write(it, bytes) }

    private fun decode(bytes: ByteArray): OwnerQuerySnapshot =
        OwnerQuerySnapshot.decode(bytes, PreparationBudget(Duration.ofSeconds(10), System::nanoTime))

    private fun rejected(bytes: ByteArray) {
        val error = assertFailsWith<OwnerQueryPreparationException> { decode(bytes) }
        assertEquals(OwnerQueryPreparationException.Stage.SNAPSHOT, error.stage)
        assertNull(error.cause)
        assertFalse(error.toString().contains(issuer))
        assertFalse(error.toString().contains("PRIVATE KEY"))
    }

    @TestFactory
    fun `closed shape and typed budgets reject malformed snapshots`(): List<DynamicTest> {
        val mutations: List<Pair<String, (MutableMap<String, Any>) -> Unit>> = listOf(
            "unknown" to { it["unknown"] = "secret" },
            "case" to { it["Issuer"] = it.remove("issuer")!! },
            "missing" to { it.remove("issuer"); Unit },
            "null" to { it["issuer"] = mapper.nullNode() },
            "scalar type" to { it["issuer"] = 7 },
            "fraction" to { it["queryTimeoutSeconds"] = 1.5 },
            "number overflow" to { it["maxInFlight"] = Long.MAX_VALUE },
            "zero query" to { it["queryTimeoutSeconds"] = 0 },
            "query exceeds request" to { it["queryTimeoutSeconds"] = 6 },
            "zero admission" to { it["maxInFlight"] = 0 },
            "excess admission" to { it["maxInFlight"] = 257 },
            "zero budget" to { it["requestTimeout"] = "PT0S" },
            "over budget" to { it["requestTimeout"] = "PT61S" },
            "overflow budget" to { it["requestTimeout"] = "PT99999999999999999999S" },
            "unitless budget" to { it["requestTimeout"] = "5" },
            "connect exceeds request" to { it["connectTimeout"] = "PT6S" },
            "negative cleanup" to { it["shutdownTimeout"] = "PT-1S" },
            "http" to { it["goOrigin"] = "http://127.0.0.1" },
            "userinfo" to { it["goOrigin"] = "https://user:secret@127.0.0.1" },
            "path" to { it["goOrigin"] = "https://127.0.0.1/path" },
            "query" to { it["goOrigin"] = "https://127.0.0.1?secret" },
            "fragment" to { it["goOrigin"] = "https://127.0.0.1#secret" },
            "credential format" to { it["goCredential"] = "short" },
            "credential reuse" to { it["goCredential"] = "B".repeat(43) },
            "duplicate credential" to { it["authorityCredentials"] = listOf("B".repeat(43), "B".repeat(43)) },
            "too many credentials" to { it["authorityCredentials"] = listOf("B".repeat(43), "C".repeat(43), "D".repeat(43)) },
            "empty credentials" to { it["authorityCredentials"] = emptyList<String>() },
            "missing signing key" to { it["signingKid"] = "absent" },
            "empty keys" to { it["keys"] = emptyList<Any>() },
            "duplicate identity" to { it["keys"] = List(2) { values().getValue("keys").let { v -> (v as List<*>)[0] } } },
            "excess keys" to { it["keys"] = List(33) { values().getValue("keys").let { v -> (v as List<*>)[0] } } },
            "bad private PEM" to { it["privateKey"] = "sensitive malformed key" },
            "bad CA" to { it["goRootCAs"] = "sensitive invalid certificate" },
            "duplicate CA" to { it["goRootCAs"] = it["goRootCAs"].toString() + "\n" + it["goRootCAs"] },
        )
        return mutations.map { (name, change) -> DynamicTest.dynamicTest(name) {
            val input = values()
            change(input)
            rejected(mapper.writeValueAsBytes(input))
        } }
    }

    @Test
    fun `duplicate keys trailing JSON and malformed UTF8 are rejected`() {
        val valid = mapper.writeValueAsString(values())
        rejected((valid.dropLast(1) + ",\"issuer\":\"other\"}").toByteArray())
        rejected((valid + " {}").toByteArray())
        rejected(byteArrayOf(0xc3.toByte(), 0x28))
        rejected(ByteArray(65537) { 32 })
        rejected(valid.replace("\"kid\":\"synthetic-key\"", "\"kid\":\"synthetic-key\",\"kid\":\"other\"").toByteArray())
        rejected(valid.replace("\"publicKey\":", "\"PublicKey\":").toByteArray())
        rejected(valid.replace(issuer, "bad\\uD800").toByteArray())
    }

    @Test
    fun `mismatched software key is refused without disclosing provider errors`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        rejected(mapper.writeValueAsBytes(values().apply { this["privateKey"] = pem("PRIVATE KEY", other.private.encoded) }))
    }

    @Test
    fun `file gone after preparation preserves issuer and caller snapshot without DB probing`() {
        val path = file()
        val client = mock(OwnerMetadataHttpClient::class.java)
        val prepared = OwnerQueryPreparation.prepare(path, primary, member, clock, Duration.ofSeconds(10), System::nanoTime) { options ->
            assertEquals("A".repeat(43), options.credential())
            assertEquals(1, options.rootCertificates!!.size)
            client
        }
        Files.delete(path)
        try {
            val one = prepared.issuer.execute()
            val two = prepared.issuer.execute()
            val signed = JWSObject.parse(one.compactToken)
            assertTrue(signed.verify(RSASSAVerifier(signing.public as RSAPublicKey)))
            val claims = signed.payload.toJSONObject()
            assertEquals("9007199254740993", claims["sub"])
            assertEquals(60L, one.expiresAt.epochSecond - one.issuedAt.epochSecond)
            assertNotEquals(claims["jti"], JWSObject.parse(two.compactToken).payload.toJSONObject()["jti"])
            assertTrue(prepared.authorityCaller.accepts("B".repeat(43)))
            assertTrue(prepared.authorityCaller.accepts("C".repeat(43)))
            assertFalse(prepared.authorityCaller.accepts("A".repeat(43)))
            assertEquals("PreparedOwnerQuery(redacted)", prepared.toString())
            assertEquals(Duration.ofSeconds(5), prepared.requestTimeout)
            verifyNoInteractions(primary)
            assertFailsWith<MemberAuthorityUnavailableException> {
                prepared.authority.execute(CheckMemberAuthorityCommand(one.compactToken, "core-api", "a".repeat(32)))
            }
        } finally {
            prepared.close()
            prepared.close()
        }
        verify(client, times(1)).close()
    }

    @Test
    fun `late expiry closes created client but never borrowed datasource`() {
        val path = file()
        val source = mock(DataSource::class.java)
        val client = mock(OwnerMetadataHttpClient::class.java)
        var nanos = 0L
        val error = assertFailsWith<OwnerQueryPreparationException> {
            OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ofSeconds(1), { nanos }) {
                nanos = 1_000_000_000
                client
            }
        }
        assertEquals(OwnerQueryPreparationException.Stage.DEADLINE, error.stage)
        verify(client, times(1)).close()
        verifyNoInteractions(source)
    }

    @Test
    fun `invalid budget input factory and cleanup errors have safe distinct stages`() {
        val source = mock(DataSource::class.java)
        val path = file()
        assertEquals(OwnerQueryPreparationException.Stage.INPUT, assertFailsWith<OwnerQueryPreparationException> {
            OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ZERO)
        }.stage)
        val failure = assertFailsWith<OwnerQueryPreparationException> {
            OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ofSeconds(10), System::nanoTime) {
                error("sensitive factory cause $path")
            }
        }
        assertEquals(OwnerQueryPreparationException.Stage.CAPABILITIES, failure.stage)
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains(path.toString()))
        val contaminated = assertFailsWith<OwnerQueryPreparationException> {
            OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ofSeconds(10), System::nanoTime) {
                throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.CAPABILITIES).apply {
                    addSuppressed(IllegalStateException("sensitive suppressed path"))
                }
            }
        }
        assertTrue(contaminated.suppressed.isEmpty())

        val client = mock(OwnerMetadataHttpClient::class.java)
        doThrow(IllegalStateException("sensitive cleanup cause")).`when`(client).close()
        val prepared = OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ofSeconds(10), System::nanoTime) { client }
        val cleanup = assertFailsWith<OwnerQueryPreparationException> { prepared.close() }
        assertEquals(OwnerQueryPreparationException.Stage.CLEANUP, cleanup.stage)
        assertNull(cleanup.cause)
        prepared.close()
        verify(client, times(1)).close()
        verifyNoInteractions(source)
    }

    @Test
    fun `bounded file rejects relative directory oversized and missing inputs without cause`() {
        for (path in listOf(Path.of("relative-secret.json"), directory, directory.resolve("absent-secret.json"), file(ByteArray(65537)))) {
            val error = assertFailsWith<OwnerQueryPreparationException> {
                OwnerQueryPreparation.prepare(path, primary, member, clock, Duration.ofSeconds(10))
            }
            assertEquals(OwnerQueryPreparationException.Stage.INPUT, error.stage)
            assertNull(error.cause)
            assertFalse(error.message!!.contains(path.toString()))
        }
    }

    @Test
    fun `real client preparation never acquires datasource and has an owned close`() {
        val source = mock(DataSource::class.java)
        OwnerQueryPreparation.prepare(file(), source, member, clock, Duration.ofSeconds(10)).use { prepared ->
            assertIs<OwnerMetadataHttpClient>(prepared.reader)
            prepared.issuer.execute()
            verifyNoInteractions(source)
        }
        verifyNoInteractions(source)
    }

    @Test
    fun `input byte mutation does not change the prepared snapshot`() {
        val bytes = mapper.writeValueAsBytes(values())
        val snapshot = decode(bytes)
        bytes.fill(0)
        assertTrue(snapshot.authorityCaller.accepts("B".repeat(43)))
        assertEquals("A".repeat(43), snapshot.clientOptions.credential())
        assertEquals("OwnerQuerySnapshot(redacted)", snapshot.toString())
    }

    @Test
    fun `interrupted cleanup and preparation preserve interrupt status without raw cause`() {
        val source = mock(DataSource::class.java)
        val path = file()
        val client = mock(OwnerMetadataHttpClient::class.java)
        doAnswer { throw InterruptedException("sensitive provider interruption") }.`when`(client).close()
        val prepared = OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ofSeconds(10), System::nanoTime) { client }
        try {
            val error = assertFailsWith<OwnerQueryPreparationException> { prepared.close() }
            assertEquals(OwnerQueryPreparationException.Stage.CLEANUP, error.stage)
            assertTrue(Thread.currentThread().isInterrupted)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
        } finally {
            Thread.interrupted()
        }
        try {
            val error = assertFailsWith<OwnerQueryPreparationException> {
                OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ofSeconds(10), System::nanoTime) {
                    throw InterruptedException("sensitive preparation interruption")
                }
            }
            assertEquals(OwnerQueryPreparationException.Stage.CAPABILITIES, error.stage)
            assertTrue(Thread.currentThread().isInterrupted)
            assertNull(error.cause)
        } finally {
            Thread.interrupted()
        }
        verifyNoInteractions(source)
    }

    @Test
    fun `prepared real TLS client works after deleting its source file`() {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(tlsIdentity, "fixture-only".toCharArray())
        }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) })
        server.createContext("/") { exchange ->
            exchange.use {
                assertEquals("/internal/trading-review/v1/accounts/9007199254740993", it.requestURI.path)
                assertEquals("Bearer " + "A".repeat(43), it.requestHeaders.getFirst("Authorization"))
                assertNotNull(it.requestHeaders.getFirst("X-RefInvest-User-Delegation"))
                assertNull(it.requestHeaders.getFirst("Baggage"))
                it.responseHeaders.add("Content-Type", "application/json")
                it.responseHeaders.add("Cache-Control", "no-store")
                val body = """{"id":"9007199254740993","venue":"BINANCE","displayName":"synthetic","status":"ACTIVE","createdAt":"2026-11-27T00:00:00Z"}""".toByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        server.start()
        val source = mock(DataSource::class.java)
        try {
            val path = file(mapper.writeValueAsBytes(values().apply { this["goOrigin"] = "https://127.0.0.1:${server.address.port}" }))
            OwnerQueryPreparation.prepare(path, source, member, clock, Duration.ofSeconds(10)).use { prepared ->
                Files.delete(path)
                val issued = prepared.issuer.execute()
                val request = OwnerMetadataReader.Request(OwnerMetadataResource.ACCOUNT, "9007199254740993",
                    "00-12345678901234567890123456789012-1234567890123456-01", null)
                val call = prepared.reader.read(request, OwnerQueryDelegation(issued.compactToken, issued.expiresAt), prepared.requestTimeout)
                assertEquals("9007199254740993", assertIs<OwnerMetadataResult.Account>(call.result.toCompletableFuture().get(10, TimeUnit.SECONDS)).id)
            }
            verifyNoInteractions(source)
        } finally {
            server.stop(0)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `Linux file and ancestor symlinks are refused`() {
        val path = file()
        val alias = directory.resolve("file-alias.json")
        Files.createSymbolicLink(alias, path)
        val parent = directory.resolve("parent-alias")
        Files.createSymbolicLink(parent, directory)
        for (candidate in listOf(alias, parent.resolve(path.fileName))) {
            assertEquals(OwnerQueryPreparationException.Stage.INPUT, assertFailsWith<OwnerQueryPreparationException> {
                OwnerQueryPreparation.prepare(candidate, primary, member, clock, Duration.ofSeconds(10))
            }.stage)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows ancestor junction is refused`() {
        val target = Files.createDirectory(directory.resolve("junction-target"))
        val path = target.resolve("snapshot.json")
        Files.write(path, mapper.writeValueAsBytes(values()))
        val alias = directory.resolve("junction-alias")
        val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), target.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Synthetic junction preparation timed out")
        }
        check(process.exitValue() == 0) { "Synthetic junction preparation failed" }
        try {
            assertEquals(OwnerQueryPreparationException.Stage.INPUT, assertFailsWith<OwnerQueryPreparationException> {
                OwnerQueryPreparation.prepare(alias.resolve("snapshot.json"), primary, member, clock, Duration.ofSeconds(10))
            }.stage)
        } finally {
            Files.delete(alias)
        }
        assertTrue(Files.exists(path))
    }
}
