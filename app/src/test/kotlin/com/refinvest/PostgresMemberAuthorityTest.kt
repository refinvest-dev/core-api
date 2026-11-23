package com.refinvest

import com.refinvest.core.auth.adapter.out.persistence.JdbcMemberAuthorityControlStore
import com.refinvest.core.auth.adapter.out.persistence.JdbcMemberAuthorityReader
import com.refinvest.core.auth.adapter.security.authority.NimbusAuthorityDelegationVerifier
import com.refinvest.core.auth.adapter.security.authority.OpaqueAuthorityCredentialVerifier
import com.refinvest.core.auth.adapter.security.delegation.NimbusOwnerQueryDelegationSigner
import com.refinvest.core.auth.adapter.security.delegation.OwnerQuerySigningKey
import com.refinvest.core.auth.adapter.web.authority.MemberAuthorityController
import com.refinvest.core.auth.application.authority.AuthenticateAuthorityCallerService
import com.refinvest.core.auth.application.authority.CheckMemberAuthorityService
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityCommand
import com.refinvest.core.auth.port.inbound.authority.MemberAuthorityUnavailableException
import com.refinvest.core.auth.port.outbound.authority.MemberAuthorityReader
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationPayload
import com.refinvest.core.shared.kernel.member.MemberId
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "AUTH_TEST_JDBC_URL", matches = ".+")
class PostgresMemberAuthorityTest {
    @Test
    fun `explicit grants allow while member existence role and absent access rows never grant`() = fixture { f ->
        assertTrue(f.check().active)
        assertEquals(2, f.check().identity?.policyRevision)
        f.jdbc.update("INSERT INTO members(id,role) VALUES (2,'ADMIN')")
        assertFalse(f.check(2).active)
        assertFalse(f.check(999).active)
        f.tx.execute { assertTrue(f.store.registerMember(MemberId(2))) }
        assertFalse(f.check(2).active)
    }

    @Test
    fun `control CAS and rollback preserve the committed policy and immutable initial registration`() = fixture { f ->
        f.tx.execute {
            assertFalse(f.store.registerMember(MemberId(1)))
            assertFalse(f.store.setMemberAllowed(MemberId(1), 1, false))
        }
        assertTrue(f.check().active)
        assertFailsWith<IllegalStateException> {
            f.tx.execute {
                assertTrue(f.store.setMemberAllowed(MemberId(1), 2, false))
                error("Synthetic rollback")
            }
        }
        assertTrue(f.check().active)
        f.tx.execute { assertTrue(f.store.setMemberAllowed(MemberId(1), 2, false)) }
        assertFalse(f.check().active)
    }

    @Test
    fun `an in flight uncommitted block allows the old snapshot and the first post commit check denies`() = fixture { f ->
        val changed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val block = executor.submit {
                f.tx.execute {
                    assertTrue(f.store.setMemberAllowed(MemberId(1), 2, false))
                    changed.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            assertTrue(changed.await(10, TimeUnit.SECONDS))
            assertTrue(f.check().active)
            release.countDown()
            block.get(15, TimeUnit.SECONDS)
            assertFalse(f.check().active)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `caller authority caller kid and jti revocations are current and isolated`() = fixture { f ->
        for (caller in listOf("core-api", "trading-review-api")) {
            f.tx.execute { assertTrue(f.store.setCallerEnabled(caller, 2, false)) }
            assertFalse(f.check().active)
            f.tx.execute { assertTrue(f.store.setCallerEnabled(caller, 3, true)) }
            assertTrue(f.check().active)
        }
        f.tx.execute { assertTrue(f.store.setKeyEnabled(ISSUER, "k1", 2, false)) }
        assertFalse(f.check().active)
        f.tx.execute { assertTrue(f.store.setKeyEnabled(ISSUER, "k1", 3, true)) }
        assertTrue(f.check().active)
        val later = Instant.ofEpochSecond(Instant.now().epochSecond + 65)
        f.tx.execute {
            f.store.revokeToken(ISSUER, hash(JTI), later)
            f.store.revokeToken(ISSUER, hash(JTI), later.minusSeconds(10))
        }
        assertFalse(f.check().active)
        assertEquals(later, f.jdbc.queryForObject("SELECT retain_until FROM auth_authority_revocations", Timestamp::class.java)?.toInstant())
        assertTrue(f.check(tokenId = "other-test-only-jti").active)
    }

    @Test
    fun `missing policy schema readiness or database state are unavailable rather than inactive`() = fixture { f ->
        f.jdbc.update("UPDATE auth_authority_policy SET ready=false")
        assertFailsWith<MemberAuthorityUnavailableException> { f.check() }
        f.jdbc.update("UPDATE auth_authority_policy SET ready=true,schema_version=2")
        assertFailsWith<MemberAuthorityUnavailableException> { f.check() }
        f.jdbc.update("DELETE FROM auth_authority_policy")
        assertFailsWith<MemberAuthorityUnavailableException> { f.check() }
        f.jdbc.execute("DROP TABLE auth_authority_revocations")
        val failure = assertFailsWith<MemberAuthorityUnavailableException> { f.check() }
        assertNull(failure.cause)
    }

    @Test
    fun `snapshot checks time again after an earlier valid signature decision`() = fixture { f ->
        val past = Instant.now().minusSeconds(120)
        val service = CheckMemberAuthorityService(f.verifier, f.reader, Clock.fixed(past, ZoneOffset.UTC))
        assertFalse(service.execute(f.command(at = past)).active)
    }

    @Test
    fun `control writes require their actual primary write transaction and reads reject caller transactions`() = fixture { f ->
        assertFailsWith<IllegalStateException> { f.store.setMemberAllowed(MemberId(1), 2, false) }
        val readOnly = TransactionTemplate(DataSourceTransactionManager(f.source)).apply { isReadOnly = true }
        assertFailsWith<IllegalStateException> { readOnly.execute { f.store.setMemberAllowed(MemberId(1), 2, false) } }
        assertFailsWith<MemberAuthorityUnavailableException> { f.tx.execute { f.check() } }
        f.otherSource().use { other ->
            assertFailsWith<IllegalStateException> {
                TransactionTemplate(DataSourceTransactionManager(other)).execute { f.store.setMemberAllowed(MemberId(1), 2, false) }
            }
        }
        assertTrue(f.check().active)
    }

    @Test
    fun `member deletion is restricted even after access is blocked`() = fixture { f ->
        f.tx.execute { f.store.setMemberAllowed(MemberId(1), 2, false) }
        assertFailsWith<Exception> { f.tx.execute { f.jdbc.update("DELETE FROM members WHERE id=1") } }
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM members", Int::class.java))
    }

    @Test
    fun `HTTP response binds the real signature and database decision without inactive identity`() = fixture { f ->
        f.http().use { h ->
            val body = f.body(h)
            val allowed = h.mvc.perform(post(PATH).header("Authorization", "Bearer $CREDENTIAL").contentType("application/json").content(body)).andReturn().response
            assertEquals(200, allowed.status)
            assertEquals("no-store", allowed.getHeader("Cache-Control"))
            val json = h.mapper.readValue(allowed.contentAsString, Map::class.java)
            assertEquals(true, json["active"])
            assertEquals("1", json["sub"])
            assertEquals("2", json["policyRevision"])
            assertEquals(setOf("active", "checkId", "tokenSha256", "checkedAt", "sub", "actor", "iss", "aud", "scope", "jti", "exp", "policyRevision"), json.keys)
            val originalToken = h.mapper.readValue(body, Map::class.java)["delegation"] as String
            assertEquals(hash(originalToken), json["tokenSha256"])
            f.tx.execute { f.store.setMemberAllowed(MemberId(1), 2, false) }
            val denied = h.mvc.perform(post(PATH).header("Authorization", "Bearer $CREDENTIAL").contentType("application/json").content(body)).andReturn().response
            val inactive = h.mapper.readValue(denied.contentAsString, Map::class.java)
            assertEquals(200, denied.status)
            assertEquals(false, inactive["active"])
            assertEquals(setOf("active", "checkId", "tokenSha256", "checkedAt"), inactive.keys)
        }
    }

    @Test
    fun `HTTP accepts only the independent service credential and retains browser CSRF protection`() = fixture { f ->
        f.http().use { h ->
            val body = f.body(h)
            for (credential in listOf("wrong", f.command().delegation, "", " ")) {
                val request = post(PATH).cookie(Cookie("access_token", "browser-cookie"))
                    .contentType("application/json").content(body)
                if (credential.isNotEmpty()) request.header("Authorization", "Bearer $credential")
                val response = h.mvc.perform(request).andReturn().response
                assertEquals(401, response.status)
                assertEquals("no-store", response.getHeader("Cache-Control"))
                assertNull(response.getHeader("WWW-Authenticate"))
                assertTrue(response.contentAsString.contains("AUTHORITY_SERVICE_AUTHENTICATION_FAILED"))
            }
            val duplicate = h.mvc.perform(post(PATH).header("Authorization", "Bearer $CREDENTIAL", "Bearer $CREDENTIAL")
                .contentType("application/json").content(body)).andReturn().response
            assertEquals(401, duplicate.status)
            assertNull(duplicate.getHeader("WWW-Authenticate"))
            assertEquals(0, f.reads.get())
            assertEquals(403, h.mvc.perform(post("/browser-probe").header("Authorization", "Bearer $CREDENTIAL")).andReturn().response.status)
        }
    }

    @Test
    fun `HTTP rejects malformed unknown duplicate oversized and trailing input before authority lookup`() = fixture { f ->
        f.http().use { h ->
            val body = f.body(h)
            val cases = listOf("{}", body.dropLast(1) + ",\"ownerId\":\"1\"}",
                body.dropLast(1) + ",\"checkId\":\"" + "0".repeat(32) + "\"}",
                body + "{}", body.replace("expectedActor", "ExpectedActor"), " ".repeat(8193),
                body.replace("\"checkId\":", "\"ignored\":"))
            for (bad in cases) {
                val response = h.mvc.perform(post(PATH).header("Authorization", "Bearer $CREDENTIAL")
                    .contentType("application/json").content(bad)).andReturn().response
                assertEquals(400, response.status)
                assertEquals("no-store", response.getHeader("Cache-Control"))
                assertFalse(response.contentAsString.contains(f.command().delegation))
            }
            assertEquals(0, f.reads.get())
        }
    }

    @Test
    fun `HTTP invalid delegation is inactive and authority outage is a safe 503`() = fixture { f ->
        f.http().use { h ->
            val invalid = h.mapper.writeValueAsString(mapOf("delegation" to "not-a-jwt", "expectedActor" to "core-api", "checkId" to "0".repeat(32)))
            val response = h.mvc.perform(post(PATH).header("Authorization", "Bearer $CREDENTIAL").contentType("application/json").content(invalid)).andReturn().response
            assertEquals(200, response.status)
            assertEquals(false, h.mapper.readValue(response.contentAsString, Map::class.java)["active"])
            assertEquals(0, f.reads.get())
            f.jdbc.update("UPDATE auth_authority_policy SET ready=false")
            val failed = h.mvc.perform(post(PATH).header("Authorization", "Bearer $CREDENTIAL").contentType("application/json").content(f.body(h))).andReturn().response
            assertEquals(503, failed.status)
            val error = h.mapper.readValue(failed.contentAsString, Map::class.java)
            assertEquals("AUTHORITY_UNAVAILABLE", error["code"])
            assertEquals(true, error["retryable"])
            assertEquals(setOf("code", "message", "traceId", "retryable"), error.keys)
        }
    }

    private class Fixture(val source: HikariDataSource, private val schema: String) {
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        val store = JdbcMemberAuthorityControlStore(source)
        val reader = JdbcMemberAuthorityReader(source, 2)
        val reads = AtomicInteger()
        val verifier = NimbusAuthorityDelegationVerifier(mapOf((ISSUER to "k1") to (pair.public as RSAPublicKey)))
        val service = CheckMemberAuthorityService(verifier, MemberAuthorityReader { reads.incrementAndGet(); reader.check(it) }, Clock.systemUTC())

        init {
            jdbc.execute("CREATE TABLE members(id BIGINT PRIMARY KEY,role VARCHAR(16) NOT NULL)")
            val ddl = requireNotNull(javaClass.getResourceAsStream("/sql/member-authority-candidate.sql")).use { String(it.readAllBytes(), Charsets.UTF_8) }
            ddl.split(';').filter { it.isNotBlank() }.forEach(jdbc::execute)
            jdbc.update("INSERT INTO auth_authority_policy VALUES(1,1,true)")
            jdbc.update("INSERT INTO members VALUES(1,'MEMBER')")
            tx.execute {
                store.registerMember(MemberId(1)); store.setMemberAllowed(MemberId(1), 1, true)
                store.registerCaller("core-api", true, false); store.setCallerEnabled("core-api", 1, true)
                store.registerCaller("trading-review-api", false, true); store.setCallerEnabled("trading-review-api", 1, true)
                store.registerKey(ISSUER, "k1"); store.setKeyEnabled(ISSUER, "k1", 1, true)
            }
        }

        fun command(member: Long = 1, tokenId: String = JTI, at: Instant = Instant.ofEpochSecond(Instant.now().epochSecond)): CheckMemberAuthorityCommand =
            CheckMemberAuthorityCommand(signer.sign(OwnerQueryDelegationPayload(MemberId(member), tokenId, Instant.ofEpochSecond(at.epochSecond))), "core-api", "0".repeat(32))

        fun check(member: Long = 1, tokenId: String = JTI) = service.execute(command(member, tokenId))
        fun http() = MemberAuthorityHttpFixture(service, AuthenticateAuthorityCallerService(OpaqueAuthorityCredentialVerifier(listOf(CREDENTIAL))))
        fun body(h: MemberAuthorityHttpFixture): String {
            val command = command()
            return h.mapper.writeValueAsString(mapOf("delegation" to command.delegation, "expectedActor" to command.expectedActor, "checkId" to command.checkId))
        }
        fun otherSource() = dataSource(schema)
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("AUTH_TEST_JDBC_URL"))
        val uri = URI(url.removePrefix("jdbc:"))
        check(uri.host in setOf("localhost", "127.0.0.1", "[::1]") && uri.path == "/refinvest_integration")
        val schema = "member_authority_test_" + UUID.randomUUID().toString().replace("-", "")
        check(Regex("member_authority_test_[0-9a-f]{32}").matches(schema))
        DriverManager.getConnection(url, System.getenv("AUTH_TEST_DB_USER"), System.getenv("AUTH_TEST_DB_PASSWORD")).use { control ->
            control.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database()").use { check(it.next() && it.getString(1) == "refinvest_integration") }
                statement.execute("CREATE SCHEMA $schema")
            }
            try {
                dataSource(schema).use { test(Fixture(it, schema)) }
            } finally {
                control.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    companion object {
        private const val ISSUER = "https://issuer.invalid/delegation"
        private const val JTI = "test-only-authority-jti"
        private const val CREDENTIAL = "A000000000000000000000000000000000000000000"
        private const val PATH = MemberAuthorityController.PATH
        private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val signingKey by lazy { OwnerQuerySigningKey.prepare("k1", pair.public as RSAPublicKey, pair.private as RSAPrivateKey) }
        private val signer by lazy { NimbusOwnerQueryDelegationSigner(ISSUER) { signingKey } }
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun dataSource(schema: String) = HikariDataSource().apply {
            jdbcUrl = System.getenv("AUTH_TEST_JDBC_URL")
            username = System.getenv("AUTH_TEST_DB_USER")
            password = System.getenv("AUTH_TEST_DB_PASSWORD")
            maximumPoolSize = 4
            minimumIdle = 0
            connectionTimeout = 5000
            addDataSourceProperty("currentSchema", schema)
        }
    }
}
