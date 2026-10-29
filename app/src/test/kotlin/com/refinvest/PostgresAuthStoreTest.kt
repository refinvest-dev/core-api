package com.refinvest

import com.refinvest.core.auth.adapter.out.persistence.*
import com.refinvest.core.auth.application.login.SocialLoginService
import com.refinvest.core.auth.application.session.RefreshSessionService
import com.refinvest.core.auth.domain.valueobject.SocialIdentityId
import com.refinvest.core.auth.port.inbound.login.SocialLoginCommand
import com.refinvest.core.auth.port.inbound.session.RefreshSessionCommand
import com.refinvest.core.auth.port.outbound.persistence.SocialIdentityStore
import com.refinvest.core.auth.port.outbound.token.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.token.IssuedAuthenticationTokens
import com.refinvest.core.member.adapter.out.persistence.*
import com.refinvest.core.member.application.create.CreateMemberService
import com.refinvest.core.member.application.get.GetMemberService
import com.refinvest.core.member.domain.valueobject.MemberRole
import com.refinvest.core.member.port.inbound.get.GetMemberResult
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import com.refinvest.core.member.port.outbound.id.MemberIdGenerator
import com.refinvest.core.subscription.adapter.out.persistence.*
import com.refinvest.core.subscription.application.create.CreateSubscriptionService
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.domain.valueobject.SocialProvider
import com.refinvest.core.shared.kernel.member.MemberId
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "AUTH_TEST_JDBC_URL", matches = ".+")
class PostgresAuthStoreTest {
    @Test
    fun `logout racing with a rotation revokes the committed replacement`() = withFixture { f ->
        val initial = session(1)
        val replacement = session(2)
        f.save(initial)
        val rotated = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val rotation = workers.submit<Boolean> {
                f.tx.execute {
                    val result = f.rotate(initial, replacement)
                    f.flush()
                    rotated.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    result
                }
            }
            assertTrue(rotated.await(15, TimeUnit.SECONDS))
            val logout = workers.submit { f.revoke(initial.familyId) }
            f.awaitLockWaiter()
            release.countDown()
            assertTrue(rotation.get(15, TimeUnit.SECONDS))
            logout.get(15, TimeUnit.SECONDS)
            assertEquals(0, f.activeCount(initial.familyId), "Family revocation must also invalidate the newly committed replacement")
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `revocation winning the race rejects a previously read credential`() = withFixture { f ->
        val initial = session(1)
        f.save(initial)
        val revoked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val logout = workers.submit {
                f.tx.execute { f.revoke(); f.flush(); revoked.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            }
            assertTrue(revoked.await(15, TimeUnit.SECONDS))
            val rotation = workers.submit<Boolean> { f.rotate(initial, session(2)) }
            f.awaitLockWaiter()
            release.countDown()
            logout.get(15, TimeUnit.SECONDS)
            assertFalse(rotation.get(15, TimeUnit.SECONDS))
            assertNull(f.load(UUID(0, 2)))
            assertEquals(0, f.activeCount(FAMILY))
        } finally {
            release.countDown(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `two refresh requests with the same credential revoke the winner family on replay`() = withFixture { f ->
        val initial = session(1)
        f.save(initial)
        val rotated = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<Boolean> {
                f.tx.execute {
                    val accepted = f.refresh(initial) != null
                    f.flush(); rotated.countDown(); check(release.await(15, TimeUnit.SECONDS))
                    accepted
                }
            }
            assertTrue(rotated.await(15, TimeUnit.SECONDS))
            val second = workers.submit<Boolean> { f.refresh(initial) != null }
            f.awaitLockWaiter()
            release.countDown()
            assertTrue(first.get(15, TimeUnit.SECONDS))
            assertFalse(second.get(15, TimeUnit.SECONDS))
            assertEquals(0, f.activeCount(FAMILY))
            assertEquals(2, f.jdbc.queryForObject("SELECT count(*) FROM refresh_sessions", Int::class.java))
            assertTrue(f.issuer.roles.all { it == "ADMIN" })
        } finally {
            release.countDown(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `revocation also fences a concurrent first append`() = withFixture { f ->
        val initial = session(1)
        val saved = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val append = workers.submit {
                f.tx.execute { f.save(initial); f.flush(); saved.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            }
            assertTrue(saved.await(15, TimeUnit.SECONDS))
            val logout = workers.submit { f.revoke() }
            f.awaitLockWaiter()
            release.countDown()
            append.get(15, TimeUnit.SECONDS); logout.get(15, TimeUnit.SECONDS)
            assertEquals(0, f.activeCount(FAMILY))
            assertNotNull(f.fenceRevokedAt(FAMILY))
        } finally {
            release.countDown(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `revoked empty family cannot be resurrected by a later append`() = withFixture { f ->
        f.revoke()
        assertFailsWith<IllegalStateException> { f.save(session(1)) }
        assertNull(f.load(UUID(0, 1)))
        assertEquals(NOW, f.fenceRevokedAt(FAMILY))
    }

    @Test
    fun `other families of the same member remain independent`() = withFixture { f ->
        val initial = session(1)
        val other = session(3, OTHER_FAMILY)
        f.save(initial); f.save(other)
        val rotated = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val rotation = workers.submit {
                f.tx.execute { f.rotate(initial, session(2)); f.flush(); rotated.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            }
            assertTrue(rotated.await(15, TimeUnit.SECONDS))
            assertTrue(f.rotate(other, session(4, OTHER_FAMILY)))
            assertEquals(1, f.activeCount(OTHER_FAMILY))
            release.countDown(); rotation.get(15, TimeUnit.SECONDS)
            f.revoke()
            assertEquals(1, f.activeCount(OTHER_FAMILY))
        } finally {
            release.countDown(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `replacement collision rolls back the rotation and leaves the existing owner untouched`() = withFixture { f ->
        val initial = session(1)
        val foreign = session(2, OTHER_FAMILY)
        f.save(initial); f.save(foreign)
        assertFailsWith<RuntimeException> {
            f.tx.execute { f.rotate(initial, session(2)); f.flush() }
        }
        assertTrue(initial.hasSameStateAs(f.load(initial.jti)!!))
        assertTrue(foreign.hasSameStateAs(f.load(foreign.jti)!!))
        assertEquals(1, f.activeCount(FAMILY))
    }

    @Test
    fun `rollback restores both family fence and credential and a later rotation succeeds`() = withFixture { f ->
        val initial = session(1)
        f.save(initial)
        assertFailsWith<IllegalStateException> {
            f.tx.execute { f.revoke(); f.flush(); error("Synthetic failure after family revocation") }
        }
        assertNull(f.fenceRevokedAt(FAMILY))
        assertTrue(initial.hasSameStateAs(f.load(initial.jti)!!))
        assertTrue(f.rotate(initial, session(2)))
    }

    @Test
    fun `append collision cannot overwrite credential ownership and expected snapshots are checked`() = withFixture { f ->
        val initial = session(1)
        f.save(initial)
        assertFailsWith<RuntimeException> { f.save(session(1, OTHER_FAMILY)) }
        val forged = RefreshSession.create(initial.jti, initial.memberId, FAMILY, "other-fingerprint", initial.issuedAt, initial.expiresAt)
        assertFalse(f.rotate(forged, session(2)))
        assertTrue(initial.hasSameStateAs(f.load(initial.jti)!!))
        assertNull(f.load(UUID(0, 2)))
        assertFailsWith<IllegalStateException> { f.store.rotate(initial.rotate(session(2), NOW)) }
    }

    @Test
    fun `repeated revocation keeps the first timestamp and rotation pointer`() = withFixture { f ->
        val initial = session(1)
        f.save(initial)
        assertTrue(f.rotate(initial, session(2)))
        f.revoke(); f.revoke(at = NOW.plusSeconds(5))
        val restored = f.load(initial.jti)!!
        assertEquals(NOW, restored.revokedAt)
        assertEquals(UUID(0, 2), restored.replacedByJti)
        assertEquals(NOW, f.fenceRevokedAt(FAMILY))
        assertEquals(NOW, f.load(UUID(0, 2))?.revokedAt)
    }

    @Test
    fun `social identity uniqueness preserves the original member and provider scope`() = withFixture { f ->
        val google = SocialIdentityId(SocialProvider.GOOGLE, "exact-subject")
        f.tx.execute { f.identities.save(SocialIdentity.create(google, MemberId(1))) }
        assertFailsWith<RuntimeException> {
            f.tx.execute { f.identities.save(SocialIdentity.create(google, MemberId(2))) }
        }
        val kakao = SocialIdentityId(SocialProvider.KAKAO, google.providerSubject)
        f.tx.execute { f.identities.save(SocialIdentity.create(kakao, MemberId(2))) }
        assertEquals(MemberId(1), f.tx.execute { f.identities.findById(google) }?.memberId)
        assertEquals(MemberId(2), f.tx.execute { f.identities.findById(kakao) }?.memberId)
        assertNull(f.tx.execute { f.identities.findById(SocialIdentityId(SocialProvider.GOOGLE, "missing")) })
    }

    @Test
    fun `social login is atomic and repeat login reads the authoritative database role`() = withFixture { f ->
        val command = SocialLoginCommand(SocialProvider.GOOGLE, "login-subject")
        val first = f.login(command)
        f.jdbc.update("UPDATE members SET role='ADMIN' WHERE id=?", first.memberId.value)
        assertEquals(first.memberId, f.login(command).memberId)
        assertEquals("ADMIN", f.issuer.roles.last())
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM members", Int::class.java))
        assertEquals("FREE", f.jdbc.queryForObject("SELECT tier FROM subscriptions", String::class.java))
        assertFailsWith<IllegalStateException> {
            f.tx.execute { f.login(SocialLoginCommand(SocialProvider.KAKAO, "rolled-back")); f.flush(); error("Synthetic login failure") }
        }
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM members", Int::class.java))
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM subscriptions", Int::class.java))
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM social_identities", Int::class.java))
        assertEquals(2, f.jdbc.queryForObject("SELECT count(*) FROM refresh_sessions", Int::class.java))
    }

    @Test
    fun `concurrent first logins keep one identity and roll back the losing member and subscription`() = withFixture { f ->
        val bothRead = CountDownLatch(2)
        val identities = object : SocialIdentityStore {
            override fun findById(id: SocialIdentityId): SocialIdentity? {
                val result = f.identities.findById(id)
                bothRead.countDown(); check(bothRead.await(15, TimeUnit.SECONDS))
                return result
            }
            override fun save(identity: SocialIdentity) = f.identities.save(identity)
        }
        val command = SocialLoginCommand(SocialProvider.GOOGLE, "concurrent-subject")
        val workers = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { workers.submit<Boolean> { runCatching { f.login(command, identities) }.isSuccess } }
            assertEquals(listOf(false, true), attempts.map { it.get(20, TimeUnit.SECONDS) }.sorted())
            listOf("members", "subscriptions", "social_identities", "refresh_sessions").forEach { table ->
                assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
            }
            assertEquals(f.tx.execute { f.identities.findById(SocialIdentityId(command.provider, command.providerSubject)) }?.memberId,
                f.login(command).memberId)
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    private class Fixture(source: HikariDataSource, factory: EntityManagerFactory, private val name: String) {
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(JpaTransactionManager(factory).apply { dataSource = source })
        private val entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
        private val repositories = JpaRepositoryFactory(entityManager)
        val store = JpaRefreshSessionStoreAdapter(repositories.getRepository(RefreshSessionJpaStore::class.java),
            repositories.getRepository(RefreshSessionFamilyLockJpaStore::class.java), entityManager)
        val identities = JpaSocialIdentityStoreAdapter(repositories.getRepository(SocialIdentityJpaStore::class.java))
        fun save(session: RefreshSession) { tx.execute { store.save(session) } }
        fun rotate(current: RefreshSession, replacement: RefreshSession): Boolean = tx.execute { store.rotate(current.rotate(replacement, NOW)) }
        fun revoke(familyId: UUID = FAMILY, at: Instant = NOW) { tx.execute { store.revokeFamily(RefreshFamilyRevocation(familyId, at)) } }
        fun fenceRevokedAt(family: UUID): Instant? = jdbc.queryForObject(
            "SELECT revoked_at FROM refresh_session_family_locks WHERE family_id=?", java.sql.Timestamp::class.java, family)?.toInstant()
        val issuer = Issuer()
        private val memberIds = AtomicLong(100)
        private val memberReader = GetMemberService(JpaMemberReaderAdapter(repositories.getRepository(MemberJpaReader::class.java)))
        private val createMember = CreateMemberService(
            JpaMemberStoreAdapter(repositories.getRepository(MemberJpaStore::class.java)), MemberIdGenerator { MemberId(memberIds.incrementAndGet()) },
            CreateSubscriptionService(JpaSubscriptionStoreAdapter(repositories.getRepository(SubscriptionJpaStore::class.java))),
            Clock.fixed(NOW, ZoneOffset.UTC))
        fun login(command: SocialLoginCommand, identities: SocialIdentityStore = this.identities) =
            tx.execute { SocialLoginService(identities, store, issuer, createMember, memberReader).execute(command) }
        fun refresh(session: RefreshSession) = tx.execute {
            RefreshSessionService(store, GetMemberUseCase { GetMemberResult(it.memberId, MemberRole.ADMIN) }, issuer,
                Clock.fixed(NOW, ZoneOffset.UTC)).execute(RefreshSessionCommand(session.jti, session.memberId, session.familyId, session.tokenFingerprint))
        }
        fun flush() = entityManager.flush()
        fun load(jti: UUID): RefreshSession? = tx.execute { store.findByJti(jti) }
        fun activeCount(familyId: UUID): Int = jdbc.queryForObject(
            "SELECT count(*) FROM refresh_sessions WHERE family_id=? AND revoked_at IS NULL", Int::class.java, familyId)!!
        fun awaitLockWaiter() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if (jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                    WHERE application_name=? AND wait_event_type='Lock')""", Boolean::class.java, name) == true) return
                Thread.sleep(10)
            }
            error("Expected a PostgreSQL Auth lock waiter")
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("AUTH_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("AUTH_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("AUTH_TEST_DB_PASSWORD"))
        val schema = "auth_store_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            val bean = LocalContainerEntityManagerFactoryBean()
            val source = HikariDataSource().apply {
                jdbcUrl = url
                username = user
                this.password = password
                poolName = schema
                maximumPoolSize = 4
                minimumIdle = 0
                connectionTimeout = 5000
                addDataSourceProperty("currentSchema", schema)
                addDataSourceProperty("ApplicationName", schema)
            }
            try {
                bean.dataSource = source
                bean.jpaVendorAdapter = HibernateJpaVendorAdapter()
                bean.setPackagesToScan("com.refinvest.core.auth.adapter.out.persistence", "com.refinvest.core.member.adapter.out.persistence", "com.refinvest.core.subscription.adapter.out.persistence")
                bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create", "hibernate.jdbc.time_zone" to "UTC"))
                bean.afterPropertiesSet()
                (1..4).map { source.connection }.forEach { it.close() }
                test(Fixture(source, bean.`object`!!, schema))
            } finally {
                bean.destroy()
                source.close()
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private class Issuer : AuthenticationTokenIssuer {
        private val ids = AtomicLong(1000)
        val roles = CopyOnWriteArrayList<String>()
        override fun issue(memberId: MemberId, role: String, refreshFamilyId: UUID?): IssuedAuthenticationTokens {
            val id = ids.incrementAndGet()
            roles += role
            return IssuedAuthenticationTokens(NOW, "access-$id", NOW.plusSeconds(900), "refresh-$id", UUID(0, id),
                refreshFamilyId ?: UUID(0, id + 10000), "synthetic-fingerprint-$id", NOW.plusSeconds(3600))
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-10-29T00:00:00Z")
        private val OTHER_FAMILY = UUID(0, 20)
        private val FAMILY = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private fun session(id: Long, family: UUID = FAMILY) = RefreshSession.create(
            jti = UUID(0, id), memberId = MemberId(1), familyId = family,
            tokenFingerprint = "synthetic-fingerprint-$id", issuedAt = NOW.minusSeconds(60), expiresAt = NOW.plusSeconds(3600))
    }
}
