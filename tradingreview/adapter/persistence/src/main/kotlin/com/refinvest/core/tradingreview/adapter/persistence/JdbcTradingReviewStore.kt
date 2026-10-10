package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcTradingReviewStore(private val jdbc: JdbcTemplate) :
    TradingAccountStore, TradingBookStore, TradingDeletionStore, TradingIdempotencyStore {
    override fun insert(account: TradingAccount) {
        jdbc.update("""INSERT INTO trading_accounts
            (id, owner_member_id, venue, display_name, status, deletion_generation, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", account.id.value, account.owner.value, account.venue.name,
            account.displayName, account.status.name, account.deletionGeneration, Timestamp.from(account.createdAt),
            Timestamp.from(account.updatedAt), account.version)
    }

    override fun find(id: TradingAccountId, owner: MemberId, forUpdate: Boolean): TradingAccount? =
        jdbc.query("SELECT * FROM trading_accounts WHERE id=? AND owner_member_id=?" + if (forUpdate) " FOR UPDATE" else "",
            { rs, _ -> account(rs) }, id.value, owner.value).firstOrNull()

    override fun list(owner: MemberId, page: Int, size: Int): TradingPage<TradingAccount> {
        val total = jdbc.queryForObject("SELECT count(*) FROM trading_accounts WHERE owner_member_id=? AND status IN ('ACTIVE','ARCHIVED')",
            Long::class.java, owner.value) ?: 0L
        val items = jdbc.query("""SELECT * FROM trading_accounts WHERE owner_member_id=? AND status IN ('ACTIVE','ARCHIVED')
            ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""", { rs, _ -> account(rs) }, owner.value, size, page.toLong() * size)
        return TradingPage(items, page, size, total)
    }

    override fun update(account: TradingAccount): Boolean = jdbc.update("""UPDATE trading_accounts SET display_name=?, status=?,
        deletion_generation=?, updated_at=?, version=version+1 WHERE id=? AND owner_member_id=? AND version=?""",
        account.displayName, account.status.name, account.deletionGeneration, Timestamp.from(account.updatedAt),
        account.id.value, account.owner.value, account.version) == 1

    override fun deleted(id: TradingAccountId, owner: MemberId): Boolean = deleted("TRADING_ACCOUNT", id.value, owner)

    override fun insert(book: TradingBook) {
        jdbc.update("""INSERT INTO trading_books (id, account_id, owner_member_id, venue, display_name,
            product_family, position_mode, settlement_asset, review_timezone, status, deletion_generation,
            created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            book.id.value, book.accountId.value, book.owner.value, book.venue.name, book.displayName,
            book.productFamily.name, book.positionMode.name, book.settlementAsset.name, book.reviewTimezone,
            book.status.name, book.deletionGeneration, Timestamp.from(book.createdAt), Timestamp.from(book.updatedAt), book.version)
    }

    override fun find(id: TradingBookId, owner: MemberId, forUpdate: Boolean): TradingBook? =
        jdbc.query("SELECT * FROM trading_books WHERE id=? AND owner_member_id=?" + if (forUpdate) " FOR UPDATE" else "",
            { rs, _ -> book(rs) }, id.value, owner.value).firstOrNull()

    override fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int): TradingPage<TradingBook> {
        val total = jdbc.queryForObject("""SELECT count(*) FROM trading_books WHERE account_id=? AND owner_member_id=?
            AND status='ACTIVE'""", Long::class.java, accountId.value, owner.value) ?: 0L
        val items = jdbc.query("""SELECT * FROM trading_books WHERE account_id=? AND owner_member_id=? AND status='ACTIVE'
            ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""", { rs, _ -> book(rs) }, accountId.value,
            owner.value, size, page.toLong() * size)
        return TradingPage(items, page, size, total)
    }

    override fun update(book: TradingBook): Boolean = jdbc.update("""UPDATE trading_books SET display_name=?, status=?,
        deletion_generation=?, updated_at=?, version=version+1 WHERE id=? AND owner_member_id=? AND version=?""",
        book.displayName, book.status.name, book.deletionGeneration, Timestamp.from(book.updatedAt),
        book.id.value, book.owner.value, book.version) == 1

    override fun markAccountBooksDeleting(accountId: TradingAccountId, now: Instant) {
        jdbc.update("""UPDATE trading_books SET status='DELETION_PENDING', deletion_generation=deletion_generation+1,
            updated_at=?, version=version+1 WHERE account_id=? AND status IN ('ACTIVE','ARCHIVED')""",
            Timestamp.from(now), accountId.value)
    }

    override fun deleted(id: TradingBookId, owner: MemberId): Boolean = deleted("TRADING_BOOK", id.value, owner)

    override fun insert(deletion: TradingDeletion) {
        jdbc.update("""INSERT INTO trading_deletion_requests (id, owner_member_id, target_type, target_id,
            requested_scope, state, deletion_generation, account_count, book_count, revoked_at, requested_at,
            updated_at, backup_purge_due_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", deletion.id, deletion.owner.value, deletion.targetType,
            deletion.targetId, deletion.requestedScope, deletion.state, deletion.deletionGeneration,
            deletion.accountCount, deletion.bookCount, Timestamp.from(deletion.revokedAt),
            Timestamp.from(deletion.requestedAt), Timestamp.from(deletion.updatedAt), Timestamp.from(deletion.backupPurgeDueAt))
    }

    override fun find(id: Long, owner: MemberId): TradingDeletion? = jdbc.query(
        "SELECT * FROM trading_deletion_requests WHERE id=? AND owner_member_id=?",
        { rs, _ -> deletion(rs) }, id, owner.value).firstOrNull()

    override fun claim(owner: MemberId, operation: String, key: String, fingerprint: String): IdempotencyClaim {
        val inserted = jdbc.update("""INSERT INTO trading_idempotency
            (owner_member_id, operation_id, request_key, payload_fingerprint) VALUES (?, ?, ?, ?)
            ON CONFLICT (owner_member_id, operation_id, request_key) DO NOTHING""",
            owner.value, operation, key, fingerprint)
        if (inserted == 1) return IdempotencyClaim.New
        val saved = jdbc.query("""SELECT payload_fingerprint, resource_id FROM trading_idempotency
            WHERE owner_member_id=? AND operation_id=? AND request_key=? FOR UPDATE""",
            { rs, _ -> rs.getString(1) to rs.getLong(2) }, owner.value, operation, key).single()
        if (saved.first != fingerprint) throw TradingFailure("IDEMPOTENCY_PAYLOAD_CONFLICT", 409)
        return IdempotencyClaim.Replayed(saved.second)
    }

    override fun complete(owner: MemberId, operation: String, key: String, resourceId: Long) {
        check(jdbc.update("""UPDATE trading_idempotency SET resource_id=? WHERE owner_member_id=? AND operation_id=?
            AND request_key=? AND resource_id IS NULL""", resourceId, owner.value, operation, key) == 1)
    }

    private fun deleted(type: String, id: Long, owner: MemberId): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM trading_deleted_resources WHERE target_type=? AND target_id=? AND owner_member_id=?)",
        Boolean::class.java, type, id, owner.value) == true

    private fun account(rs: ResultSet) = TradingAccount.restore(TradingAccountId(rs.getLong("id")),
        MemberId(rs.getLong("owner_member_id")), TradingVenue.valueOf(rs.getString("venue")),
        rs.getString("display_name"), TradingLifecycleStatus.valueOf(rs.getString("status")),
        rs.getLong("deletion_generation"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"))

    private fun book(rs: ResultSet) = TradingBook.restore(TradingBookId(rs.getLong("id")),
        TradingAccountId(rs.getLong("account_id")), MemberId(rs.getLong("owner_member_id")),
        TradingVenue.valueOf(rs.getString("venue")), rs.getString("display_name"),
        TradingProductFamily.valueOf(rs.getString("product_family")),
        TradingPositionMode.valueOf(rs.getString("position_mode")),
        TradingSettlementAsset.valueOf(rs.getString("settlement_asset")), rs.getString("review_timezone"),
        TradingLifecycleStatus.valueOf(rs.getString("status")), rs.getLong("deletion_generation"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"))

    private fun deletion(rs: ResultSet) = TradingDeletion(rs.getLong("id"), MemberId(rs.getLong("owner_member_id")),
        rs.getString("target_type"), rs.getLong("target_id"), rs.getString("requested_scope"), rs.getString("state"),
        rs.getLong("deletion_generation"),
        rs.getLong("account_count"), rs.getLong("book_count"), rs.getTimestamp("revoked_at").toInstant(),
        rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("backup_purge_due_at").toInstant(),
        rs.getTimestamp("completed_at")?.toInstant(), rs.getString("failure_code"))
}
