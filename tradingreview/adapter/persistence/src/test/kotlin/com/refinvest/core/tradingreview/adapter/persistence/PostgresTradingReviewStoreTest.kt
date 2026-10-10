package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.IdempotencyClaim
import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingDeletion
import com.refinvest.core.tradingreview.port.TradingIdGenerator
import com.refinvest.core.tradingreview.port.TradingMemberProvider
import com.refinvest.core.tradingreview.application.TradingReviewService
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@EnabledIfEnvironmentVariable(named = "TRADING_REVIEW_TEST_JDBC_URL", matches = ".+")
class PostgresTradingReviewStoreTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `migration and JDBC adapter preserve ownership and optimistic version`() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = JdbcTradingReviewStore(jdbc)
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(101), MemberId(1), TradingVenue.BINANCE, "One", now)
        store.insert(account)
        val book = TradingBook.create(TradingBookId(201), account, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        store.insert(book)

        assertNotNull(store.find(account.id, account.owner))
        assertEquals(null, store.find(account.id, MemberId(2)))
        assertEquals(null, store.find(book.id, MemberId(2)))
        assertEquals(1, store.list(account.owner, 0, 20).total)
        assertEquals(1, store.list(account.id, account.owner, 0, 20).total)
        assertTrue(store.update(account.rename("Two", now.plusSeconds(1))))
        assertFalse(store.update(account.rename("Stale", now.plusSeconds(2))))
        assertEquals("Two", store.find(account.id, account.owner)?.displayName)
        assertTrue(store.update(book.requestDeletion(now.plusSeconds(1))))
        assertEquals(1, store.find(book.id, book.owner)?.deletionGeneration)
    }

    @Test
    fun `database constraints and idempotency key enforce the declared boundary`() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = JdbcTradingReviewStore(jdbc)
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(102), MemberId(1), TradingVenue.BINANCE, "One", now)
        store.insert(account)
        val ownBook = TradingBook.create(TradingBookId(202), account, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        store.insert(ownBook)
        // The contract permits another Book with the same family and mode under one Account.
        store.insert(TradingBook.create(TradingBookId(203), account, "Second", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now))
        assertEquals(2, store.list(account.id, account.owner, 0, 20).total)
        assertFailsWith<Exception> {
            jdbc.update("""INSERT INTO trading_books (id, account_id, owner_member_id, venue, display_name,
                product_family, position_mode, settlement_asset, review_timezone, status, created_at, updated_at)
                VALUES (204, 102, 2, 'BINANCE', 'Wrong owner', 'LINEAR_PERPETUAL', 'ONE_WAY', 'USDT', 'UTC',
                'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""")
        }
        assertIs<IdempotencyClaim.New>(store.claim(MemberId(1), "createTradingAccount", "same-key", "a".repeat(64)))
        store.complete(MemberId(1), "createTradingAccount", "same-key", 102)
        assertEquals(102L, (store.claim(MemberId(1), "createTradingAccount", "same-key", "a".repeat(64))
            as IdempotencyClaim.Replayed).resourceId)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            store.claim(MemberId(1), "createTradingAccount", "same-key", "b".repeat(64))
        }.code)
        assertIs<IdempotencyClaim.New>(store.claim(MemberId(1), "createTradingBook", "same-key", "a".repeat(64)))
    }

    @Test
    fun `concurrent claims return one committed resource and rolled back claims can retry`() = withDatabase { first ->
        val firstStore = JdbcTradingReviewStore(JdbcTemplate(SingleConnectionDataSource(first, true)))
        members(JdbcTemplate(SingleConnectionDataSource(first, true)))
        DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
            System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { second ->
            second.schema = first.schema
            second.autoCommit = false
            val secondStore = JdbcTradingReviewStore(JdbcTemplate(SingleConnectionDataSource(second, true)))
            first.autoCommit = false
            assertIs<IdempotencyClaim.New>(firstStore.claim(MemberId(1), "createTradingAccount", "race", "a".repeat(64)))
            firstStore.complete(MemberId(1), "createTradingAccount", "race", 400)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val replay = executor.submit<IdempotencyClaim> {
                    secondStore.claim(MemberId(1), "createTradingAccount", "race", "a".repeat(64))
                }
                Thread.sleep(150)
                assertFalse(replay.isDone)
                first.commit()
                assertEquals(400L, (replay.get(10, TimeUnit.SECONDS) as IdempotencyClaim.Replayed).resourceId)
                second.commit()

                assertIs<IdempotencyClaim.New>(firstStore.claim(MemberId(1), "createTradingAccount", "rollback", "b".repeat(64)))
                first.rollback()
                assertIs<IdempotencyClaim.New>(secondStore.claim(MemberId(1), "createTradingAccount", "rollback", "b".repeat(64)))
                second.rollback()
            } finally {
                executor.shutdownNow()
                first.autoCommit = true
            }
        }
    }

    @Test
    fun `application transactions enforce owner replay deletion and tombstone`() = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val store = JdbcTradingReviewStore(jdbc)
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        members(jdbc)
        val member = object : TradingMemberProvider {
            var id = MemberId(1)
            override fun currentMemberId() = id
        }
        val ids = object : TradingIdGenerator {
            var next = 1000L
            override fun accountId() = TradingAccountId(next++)
            override fun bookId() = TradingBookId(next++)
            override fun deletionId() = next++
        }
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = TradingReviewService(member, ids, store, store, store, store, clock)
        val account = transactions.execute { service.create(TradingVenue.BINANCE, "Primary", "create-account") }
        assertEquals(account.id, transactions.execute {
            service.create(TradingVenue.BINANCE, "Primary", "create-account")
        }.id)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            transactions.execute { service.create(TradingVenue.BINANCE, "Different", "create-account") }
        }.code)
        member.id = MemberId(2)
        assertEquals(404, assertFailsWith<TradingFailure> { service.get(account.id) }.httpStatus)
        assertEquals(404, assertFailsWith<TradingFailure> {
            transactions.execute { service.create(account.id, "Other", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "foreign-book") }
        }.httpStatus)
        member.id = MemberId(1)
        val book = transactions.execute { service.create(account.id, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-book") }
        assertEquals(book.id, transactions.execute { service.create(account.id, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-book") }.id)
        assertEquals(422, assertFailsWith<TradingFailure> {
            transactions.execute { service.create(account.id, "Bad timezone", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "+09:00", "invalid-timezone") }
        }.httpStatus)
        val deletion = transactions.execute { service.delete(account.id, "delete-account") }
        assertEquals(1L, deletion.deletionGeneration)
        assertEquals(410, assertFailsWith<TradingFailure> { service.get(account.id) }.httpStatus)
        assertEquals(410, assertFailsWith<TradingFailure> {
            transactions.execute { service.create(account.id, "Blocked", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "new-book") }
        }.httpStatus)
        TradingDeletionWorker(jdbc, transactions, clock).purgePending()
        assertEquals("COMPLETED", service.get(deletion.id).state)
        assertEquals(410, assertFailsWith<TradingFailure> { service.get(book.id) }.httpStatus)
        member.id = MemberId(2)
        assertEquals(404, assertFailsWith<TradingFailure> { service.get(book.id) }.httpStatus)
    }

    @Test
    fun `failed primary purge remains inaccessible and does not report completion`() = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val store = JdbcTradingReviewStore(jdbc)
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(301), MemberId(1), TradingVenue.BINANCE, "Account", now)
        store.insert(account)
        val book = TradingBook.create(TradingBookId(302), account, "Book", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        store.insert(book)
        assertTrue(store.update(book.requestDeletion(now)))
        store.insert(TradingDeletion(303, MemberId(1), "TRADING_BOOK", book.id.value, "BOOK_SUBTREE",
            "REQUESTED", 1, 0, 1, now, now, now, now.plusSeconds(30L * 24 * 3600)))
        jdbc.execute("CREATE TABLE blocking_child (id BIGINT PRIMARY KEY, book_id BIGINT NOT NULL REFERENCES trading_books(id))")
        jdbc.update("INSERT INTO blocking_child(id, book_id) VALUES (1, 302)")
        val worker = TradingDeletionWorker(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)),
            Clock.fixed(now, ZoneOffset.UTC))
        repeat(5) { worker.purgePending() }
        val result = store.find(303L, MemberId(1))!!
        assertEquals("FAILED", result.state)
        assertEquals("DERIVED_DATA_DELETION_FAILED", result.failureCode)
        assertNotNull(store.find(book.id, MemberId(1)))
    }

    private fun members(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO members(id, role, created_at) VALUES (1, 'USER', CURRENT_TIMESTAMP)")
        jdbc.update("INSERT INTO members(id, role, created_at) VALUES (2, 'USER', CURRENT_TIMESTAMP)")
    }

    private fun withDatabase(test: (Connection) -> Unit) {
        val url = requireNotNull(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD"))
        DriverManager.getConnection(url, user, password).use { connection ->
            val schema = "trading_test_" + UUID.randomUUID().toString().replace("-", "")
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                val migration = javaClass.getResourceAsStream("/db/migration/V1__trading_account_book.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                }
                test(connection)
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
