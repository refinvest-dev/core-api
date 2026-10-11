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
        TradingDeletionWorker(jdbc, transactions, emptyStorage(), clock).purgePending()
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
            emptyStorage(), Clock.fixed(now, ZoneOffset.UTC))
        repeat(5) { worker.purgePending() }
        val result = store.find(303L, MemberId(1))!!
        assertEquals("FAILED", result.state)
        assertEquals("DELETION_PARTIALLY_COMPLETED", result.failureCode)
        assertNotNull(store.find(book.id, MemberId(1)))
    }

    @Test
    fun importUploadReplayConflictAndDeletionUseRealPostgres(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val accountStore = JdbcTradingReviewStore(jdbc)
        val importStore = JdbcTradingImportStore(jdbc)
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(601), MemberId(1), TradingVenue.BINANCE, "A", now)
        accountStore.insert(account)
        val book = TradingBook.create(TradingBookId(602), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        accountStore.insert(book)
        val ids = object : TradingIdGenerator {
            var next = 603L
            override fun accountId() = TradingAccountId(next++)
            override fun bookId() = TradingBookId(next++)
            override fun importSessionId() = TradingImportSessionId(next++)
            override fun sourceArtifactId() = TradingSourceArtifactId(next++)
            override fun deletionId() = next++
        }
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val service = com.refinvest.core.tradingreview.application.TradingImportService(
            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) }, ids, accountStore, accountStore, importStore, importStore,
            accountStore, accountStore, storage, object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                override fun currentJson() = "{\"versionSetHash\":\"exact\"}"
            }, transactions, Clock.fixed(now, ZoneOffset.UTC))
        val session = transactions.execute { service.create(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY,
            null, null, "create-import") }
        assertEquals(session.id, transactions.execute { service.create(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY,
            null, null, "create-import") }.id)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            transactions.execute { service.create(book.id, "Asia/Seoul", "UTC", TradingPositionMode.ONE_WAY,
                null, null, "create-import") }
        }.code)
        val tradeBytes = "synthetic,one\n".toByteArray()
        val positionBytes = "synthetic,two\n".toByteArray()
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun upload(role: TradingArtifactRole, bytes: ByteArray, key: String) = service.upload(session.id, role,
            "sample.csv", "text/csv", bytes.size.toLong(), sha(bytes), "AUTO",
            java.io.ByteArrayInputStream(bytes), key)
        val trade = upload(TradingArtifactRole.TRADE_HISTORY, tradeBytes, "trade-first")
        assertTrue(trade.created)
        assertEquals(null, importStore.find(trade.artifact.id, MemberId(1))?.filename)
        assertFalse(service.get(session.id).session.canValidate(importStore.list(session.id)))
        val replay = upload(TradingArtifactRole.TRADE_HISTORY, tradeBytes, "trade-second")
        assertFalse(replay.created)
        assertEquals(trade.artifact.id, replay.artifact.id)
        assertEquals("ARTIFACT_ROLE_CONFLICT", assertFailsWith<TradingFailure> {
            upload(TradingArtifactRole.TRADE_HISTORY, "other\n".toByteArray(), "trade-conflict")
        }.code)
        val position = upload(TradingArtifactRole.POSITION_HISTORY, positionBytes, "position-first")
        assertTrue(position.created)
        assertTrue(service.get(session.id).session.canValidate(importStore.list(session.id)))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
        val rawDelete = transactions.execute { service.deleteArtifact(position.artifact.id, "delete-raw") }
        assertEquals(rawDelete.id, transactions.execute { service.deleteArtifact(position.artifact.id, "delete-raw-again") }.id)
        TradingDeletionWorker(jdbc, transactions, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals("COMPLETED", accountStore.find(rawDelete.id, MemberId(1))?.state)
        assertFalse(storage.exists(position.artifact.objectKey!!))
        assertFalse(service.get(session.id).session.canValidate(importStore.list(session.id)))
        assertEquals(null, jdbc.queryForObject("SELECT wrapped_dek FROM trading_source_artifacts WHERE id=?",
            ByteArray::class.java, position.artifact.id.value))
        val deletion = transactions.execute { service.delete(session.id, "delete-import") }
        assertEquals(deletion.id, transactions.execute { service.delete(session.id, "delete-import-again") }.id)
        assertEquals(410, assertFailsWith<TradingFailure> { service.get(session.id) }.httpStatus)
        TradingDeletionWorker(jdbc, transactions, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals("COMPLETED", accountStore.find(deletion.id, MemberId(1))?.state)
        assertFalse(storage.exists(trade.artifact.objectKey!!))
        assertFalse(storage.exists(position.artifact.objectKey!!))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
    }

    @Test
    fun concurrentUploadsPublishOneArtifactPerRole(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = withDatabase { setup ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(setup, true))
        members(jdbc)
        val baseStore = JdbcTradingReviewStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(701), MemberId(1), TradingVenue.BINANCE, "A", now)
        baseStore.insert(account)
        val book = TradingBook.create(TradingBookId(702), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        baseStore.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(703), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        JdbcTradingImportStore(jdbc).insert(session)
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val nextId = java.util.concurrent.atomic.AtomicLong(704)
        fun runPair(firstBytes: ByteArray, secondBytes: ByteArray, role: TradingArtifactRole): List<Result<com.refinvest.core.tradingreview.port.UploadArtifactResult>> {
            val start = java.util.concurrent.CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val inputs = listOf(firstBytes, secondBytes)
                val futures = inputs.mapIndexed { index, bytes -> executor.submit<Result<com.refinvest.core.tradingreview.port.UploadArtifactResult>> {
                    DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                        System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                        connection.schema = setup.schema
                        val dataSource = SingleConnectionDataSource(connection, true)
                        val workerJdbc = JdbcTemplate(dataSource)
                        val reviewStore = JdbcTradingReviewStore(workerJdbc)
                        val importStore = JdbcTradingImportStore(workerJdbc)
                        val ids = object : TradingIdGenerator {
                            override fun accountId() = TradingAccountId(nextId.getAndIncrement())
                            override fun bookId() = TradingBookId(nextId.getAndIncrement())
                            override fun sourceArtifactId() = TradingSourceArtifactId(nextId.getAndIncrement())
                            override fun deletionId() = nextId.getAndIncrement()
                        }
                        val service = com.refinvest.core.tradingreview.application.TradingImportService(
                            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
                            ids, reviewStore, reviewStore, importStore, importStore, reviewStore, reviewStore,
                            storage, object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                                override fun currentJson() = "{}"
                            }, TransactionTemplate(DataSourceTransactionManager(dataSource)), Clock.fixed(now, ZoneOffset.UTC))
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        start.await(10, TimeUnit.SECONDS)
                        runCatching { service.upload(session.id, role, "synthetic.csv", "text/csv",
                            bytes.size.toLong(), hash, "AUTO", java.io.ByteArrayInputStream(bytes), "race-$role-$index") }
                    }
                } }
                start.countDown()
                return futures.map { it.get(20, TimeUnit.SECONDS) }
            } finally { executor.shutdownNow() }
        }
        val same = runPair("same\n".toByteArray(), "same\n".toByteArray(), TradingArtifactRole.TRADE_HISTORY)
        assertTrue(same.all { it.isSuccess })
        assertEquals(1, same.count { it.getOrThrow().created })
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts WHERE artifact_role = 'TRADE_HISTORY'", Long::class.java))
        val different = runPair("first\n".toByteArray(), "second\n".toByteArray(), TradingArtifactRole.POSITION_HISTORY)
        assertEquals(1, different.count { it.isSuccess })
        assertEquals("ARTIFACT_ROLE_CONFLICT", (different.single { it.isFailure }.exceptionOrNull() as TradingFailure).code)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts WHERE artifact_role = 'POSITION_HISTORY'", Long::class.java))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
        java.nio.file.Files.list(root.resolve("staging")).use { assertEquals(0L, it.count()) }
        java.nio.file.Files.list(root.resolve("objects")).use { assertEquals(2L, it.count()) }
    }

    @Test
    fun deletionDuringStreamingUploadBlocksPublicationAndRemovesTheObject(
        @org.junit.jupiter.api.io.TempDir root: java.nio.file.Path,
    ) = withDatabase { setup ->
        val dataSource = SingleConnectionDataSource(setup, true)
        val jdbc = JdbcTemplate(dataSource)
        members(jdbc)
        val reviewStore = JdbcTradingReviewStore(jdbc)
        val importStore = JdbcTradingImportStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(801), MemberId(1), TradingVenue.BINANCE, "A", now)
        reviewStore.insert(account)
        val book = TradingBook.create(TradingBookId(802), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        reviewStore.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(803), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        importStore.insert(session)
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val nextId = java.util.concurrent.atomic.AtomicLong(804)
        fun service(connection: Connection): com.refinvest.core.tradingreview.application.TradingImportService {
            val source = SingleConnectionDataSource(connection, true)
            val connectionJdbc = JdbcTemplate(source)
            val reviews = JdbcTradingReviewStore(connectionJdbc)
            val imports = JdbcTradingImportStore(connectionJdbc)
            val ids = object : TradingIdGenerator {
                override fun accountId() = TradingAccountId(nextId.getAndIncrement())
                override fun bookId() = TradingBookId(nextId.getAndIncrement())
                override fun sourceArtifactId() = TradingSourceArtifactId(nextId.getAndIncrement())
                override fun deletionId() = nextId.getAndIncrement()
            }
            return com.refinvest.core.tradingreview.application.TradingImportService(
                object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) }, ids,
                reviews, reviews, imports, imports, reviews, reviews, storage,
                object : com.refinvest.core.tradingreview.port.TradingVersionPins { override fun currentJson() = "{}" },
                TransactionTemplate(DataSourceTransactionManager(source)), Clock.fixed(now, ZoneOffset.UTC))
        }
        val reading = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val raw = "synthetic\n".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val upload = executor.submit<Result<com.refinvest.core.tradingreview.port.UploadArtifactResult>> {
                DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                    System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                    connection.schema = setup.schema
                    val stream = object : java.io.ByteArrayInputStream(raw) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            reading.countDown()
                            check(resume.await(10, TimeUnit.SECONDS))
                            return super.read(buffer, offset, length)
                        }
                    }
                    runCatching { service(connection).upload(session.id, TradingArtifactRole.TRADE_HISTORY,
                        "synthetic.csv", "text/csv", raw.size.toLong(), hash, "AUTO", stream, "upload-race") }
                }
            }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            val deletion = TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                service(setup).delete(session.id, "delete-race")
            }
            assertNotNull(deletion)
            resume.countDown()
            val outcome = upload.get(20, TimeUnit.SECONDS)
            assertTrue(outcome.isFailure)
            assertEquals("RESOURCE_STATE_CONFLICT", (outcome.exceptionOrNull() as TradingFailure).code)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
            java.nio.file.Files.list(root.resolve("staging")).use { assertEquals(0L, it.count()) }
            java.nio.file.Files.list(root.resolve("objects")).use { assertEquals(0L, it.count()) }
            TradingDeletionWorker(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), storage,
                Clock.fixed(now, ZoneOffset.UTC)).purgePending()
            assertEquals("COMPLETED", reviewStore.find(deletion.id, MemberId(1))?.state)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test
    fun parentDeletionWinsAgainstSessionCreation() = withDatabase { setup ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(setup, true))
        members(jdbc)
        val store = JdbcTradingReviewStore(jdbc)
        for ((index, target) in listOf("account", "book").withIndex()) {
            val accountId = TradingAccountId(901 + index * 10L)
            val bookId = TradingBookId(902 + index * 10L)
            val account = TradingAccount.create(accountId, MemberId(1), TradingVenue.BINANCE, "A", now)
            store.insert(account)
            val book = TradingBook.create(bookId, account, "B", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
            store.insert(book)
            setup.autoCommit = false
            val table = if (target == "account") "trading_accounts" else "trading_books"
            val targetId = if (target == "account") accountId.value else bookId.value
            jdbc.update("UPDATE $table SET status='DELETION_PENDING', deletion_generation=deletion_generation+1 WHERE id=?",
                targetId)
            val started = java.util.concurrent.CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val creation = executor.submit<Result<TradingImportSession>> {
                    DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                        System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                        connection.schema = setup.schema
                        val dataSource = SingleConnectionDataSource(connection, true)
                        val workerJdbc = JdbcTemplate(dataSource)
                        val reviews = JdbcTradingReviewStore(workerJdbc)
                        val imports = JdbcTradingImportStore(workerJdbc)
                        val ids = object : TradingIdGenerator {
                            override fun accountId() = TradingAccountId(9999)
                            override fun bookId() = TradingBookId(9999)
                            override fun importSessionId() = TradingImportSessionId(903 + index * 10L)
                            override fun deletionId() = 9999L
                        }
                        val storage = emptyStorage()
                        val service = com.refinvest.core.tradingreview.application.TradingImportService(
                            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
                            ids, reviews, reviews, imports, imports, reviews, reviews, storage,
                            object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                                override fun currentJson() = "{}"
                            }, TransactionTemplate(DataSourceTransactionManager(dataSource)), Clock.fixed(now, ZoneOffset.UTC))
                        started.countDown()
                        runCatching { TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                            service.create(bookId, "UTC", "UTC", TradingPositionMode.ONE_WAY, null, null,
                                "create-$target")
                        } }
                    }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                Thread.sleep(150)
                assertFalse(creation.isDone)
                setup.commit()
                val failure = creation.get(10, TimeUnit.SECONDS).exceptionOrNull() as TradingFailure
                assertEquals("RESOURCE_DELETED", failure.code)
                assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
            } finally {
                setup.rollback()
                setup.autoCommit = true
                executor.shutdownNow()
            }
        }
    }

    private fun emptyStorage() = object : com.refinvest.core.tradingreview.port.RawArtifactStorage {
        override fun putStaging(source: java.io.InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId):
            com.refinvest.core.tradingreview.port.StagedRawArtifact = error("Unused")
        override fun promote(staged: com.refinvest.core.tradingreview.port.StagedRawArtifact, finalKey: String) = Unit
        override fun completePublication(finalKey: String) = Unit
        override fun open(key: String, wrappedDek: ByteArray, keyVersion: String,
                          artifactId: TradingSourceArtifactId): java.io.InputStream = error("Unused")
        override fun delete(key: String) = Unit
        override fun exists(key: String) = false
        override fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean) = 0
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
                val migration2 = javaClass.getResourceAsStream("/db/migration/V2__trading_import_artifacts.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration2.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                }
                test(connection)
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
