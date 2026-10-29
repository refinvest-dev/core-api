package com.refinvest

import com.refinvest.core.member.adapter.out.persistence.*
import com.refinvest.core.member.application.create.CreateMemberService
import com.refinvest.core.member.domain.valueobject.MemberRole
import com.refinvest.core.member.port.inbound.create.CreateMemberCommand
import com.refinvest.core.member.port.outbound.id.MemberIdGenerator
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.adapter.out.persistence.*
import com.refinvest.core.subscription.application.create.CreateSubscriptionService
import com.refinvest.core.subscription.application.upgrade.UpgradeSubscriptionService
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionCommand
import com.refinvest.core.subscription.port.inbound.upgrade.UpgradeSubscriptionCommand
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "MEMBER_SUBSCRIPTION_TEST_JDBC_URL", matches = ".+")
class PostgresMemberSubscriptionStoreTest {
    @Test
    fun `member creation persists aggregate and default subscription atomically`() = withFixture { f ->
        val result = f.tx.execute { f.memberService.execute(CreateMemberCommand) }!!
        assertEquals(MemberId(1), result.memberId)
        val member = f.memberReader.findById(result.memberId)!!
        assertEquals(MemberRole.MEMBER, member.role)
        assertEquals(TIME, member.createdAt)
        assertEquals(SubscriptionTier.FREE, f.reader.findByMemberId(result.memberId)?.tier)
        f.jdbc.update("UPDATE members SET role='ADMIN' WHERE id=1")
        assertEquals(MemberRole.ADMIN, f.memberReader.findById(result.memberId)?.role)
        assertNull(f.memberReader.findById(MemberId(999)))
        assertNull(f.reader.findByMemberId(MemberId(999)))
    }

    @Test
    fun `repeated default creation preserves a paid subscription`() = withFixture { f ->
        f.seedMember()
        assertEquals(SubscriptionTier.PRO, f.upgrade())
        assertEquals(SubscriptionTier.PRO, f.create())
        assertEquals(SubscriptionTier.PRO, f.reader.findByMemberId(MemberId(1))?.tier)
    }

    @Test
    fun `upgrade creates a missing subscription and repeated upgrade stays pro`() = withFixture { f ->
        f.seedMember()
        repeat(2) { assertEquals(SubscriptionTier.PRO, f.upgrade()) }
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM subscriptions", Int::class.java))
    }

    @Test
    fun `command load requires a transaction while reader accepts unknown members`() = withFixture { f ->
        f.seedMember()
        assertFailsWith<IllegalStateException> { f.store.findByMemberId(MemberId(1)) }
        assertFailsWith<IllegalArgumentException> { f.tx.execute { f.store.findByMemberId(MemberId(999)) } }
        assertNull(f.reader.findByMemberId(MemberId(999)))
    }

    @Test
    fun `concurrent initial creation and upgrade serialize even when subscription row is absent`() = withFixture { f ->
        f.seedMember()
        race(f, firstUpgrade = false)
    }

    @Test
    fun `concurrent default creation waits for first upgrade and preserves pro`() = withFixture { f ->
        f.seedMember()
        race(f, firstUpgrade = true)
    }

    @Test
    fun `readers and other members do not wait for an upgrade command`() = withFixture { f ->
        f.seedMember()
        f.seedMember(2)
        f.create()
        val saved = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                f.tx.execute {
                    f.upgrade()
                    f.flush()
                    saved.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            assertTrue(saved.await(15, TimeUnit.SECONDS))
            val waiting = pool.submit<SubscriptionTier> { f.create() }
            f.awaitLockWaiter()
            assertEquals(SubscriptionTier.FREE, f.reader.findByMemberId(MemberId(1))?.tier)
            assertEquals(SubscriptionTier.FREE, f.create(2))
            release.countDown()
            first.get(15, TimeUnit.SECONDS)
            assertEquals(SubscriptionTier.PRO, waiting.get(15, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `rollback removes new member and subscription and keeps an existing free tier`() = withFixture { f ->
        assertFailsWith<IllegalStateException> {
            f.tx.execute {
                f.memberService.execute(CreateMemberCommand)
                f.flush()
                error("Synthetic failure after both aggregates were inserted")
            }
        }
        assertNull(f.memberReader.findById(MemberId(1)))
        assertNull(f.reader.findByMemberId(MemberId(1)))
        f.seedMember()
        f.create()
        assertFailsWith<IllegalStateException> {
            f.tx.execute { f.upgrade(); f.flush(); error("Synthetic upgrade failure") }
        }
        assertEquals(SubscriptionTier.FREE, f.reader.findByMemberId(MemberId(1))?.tier)
        assertEquals(SubscriptionTier.PRO, f.upgrade())
    }

    private fun race(f: Fixture, firstUpgrade: Boolean) {
        val saved = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<SubscriptionTier> {
                f.tx.execute {
                    val result = if (firstUpgrade) f.upgrade() else f.create()
                    f.flush()
                    saved.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    result
                }!!
            }
            assertTrue(saved.await(15, TimeUnit.SECONDS))
            val second = pool.submit<SubscriptionTier> { if (firstUpgrade) f.create() else f.upgrade() }
            f.awaitLockWaiter()
            release.countDown()
            assertEquals(if (firstUpgrade) SubscriptionTier.PRO else SubscriptionTier.FREE, first.get(15, TimeUnit.SECONDS))
            assertEquals(SubscriptionTier.PRO, second.get(15, TimeUnit.SECONDS))
            assertEquals(SubscriptionTier.PRO, f.reader.findByMemberId(MemberId(1))?.tier)
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    private class Fixture(source: HikariDataSource, factory: EntityManagerFactory, private val name: String) {
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(JpaTransactionManager(factory).apply { dataSource = source })
        private val entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
        private val repositories = JpaRepositoryFactory(entityManager)
        val store = JpaSubscriptionStoreAdapter(repositories.getRepository(SubscriptionJpaStore::class.java))
        val reader = JpaSubscriptionReaderAdapter(repositories.getRepository(SubscriptionJpaReader::class.java))
        val memberReader = JpaMemberReaderAdapter(repositories.getRepository(MemberJpaReader::class.java))
        private val createService = CreateSubscriptionService(store)
        private val upgradeService = UpgradeSubscriptionService(store)
        val memberService = CreateMemberService(
            JpaMemberStoreAdapter(repositories.getRepository(MemberJpaStore::class.java)),
            MemberIdGenerator { MemberId(1) }, createService, Clock.fixed(TIME, ZoneOffset.UTC))

        fun seedMember(id: Long = 1) { jdbc.update("INSERT INTO members (id, role, created_at) VALUES (?, 'MEMBER', ?)", id, java.sql.Timestamp.from(TIME)) }
        fun create(id: Long = 1): SubscriptionTier = tx.execute { createService.execute(CreateSubscriptionCommand(MemberId(id))).tier }!!
        fun upgrade(): SubscriptionTier = tx.execute { upgradeService.execute(UpgradeSubscriptionCommand(MemberId(1))).tier }!!
        fun flush() = entityManager.flush()
        fun awaitLockWaiter() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if (jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                    WHERE application_name=? AND wait_event_type='Lock')""", Boolean::class.java, name) == true) return
                Thread.sleep(10)
            }
            error("Expected a PostgreSQL command lock waiter")
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("MEMBER_SUBSCRIPTION_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("MEMBER_SUBSCRIPTION_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("MEMBER_SUBSCRIPTION_TEST_DB_PASSWORD"))
        val schema = "member_subscription_test_" + UUID.randomUUID().toString().replace("-", "")
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
                bean.setPackagesToScan("com.refinvest.core.member.adapter.out.persistence", "com.refinvest.core.subscription.adapter.out.persistence")
                bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create", "hibernate.jdbc.time_zone" to "UTC"))
                bean.afterPropertiesSet()
                // Reserve separate physical connections before starting competing transactions.
                (1..4).map { source.connection }.forEach { it.close() }
                test(Fixture(source, bean.`object`!!, schema))
            } finally {
                bean.destroy()
                source.close()
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    companion object {
        private val TIME = Instant.parse("2026-10-01T00:00:00.123456Z")
    }
}
