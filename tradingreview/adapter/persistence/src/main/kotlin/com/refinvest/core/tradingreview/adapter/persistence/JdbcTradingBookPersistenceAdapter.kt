package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.book.TradingBookReader
import com.refinvest.core.tradingreview.port.book.TradingBookStore
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcTradingBookPersistenceAdapter(private val jdbc: JdbcTemplate) : TradingBookStore, TradingBookReader {
    private val sql = NamedParameterJdbcTemplate(jdbc)

    @Transactional
    override fun insert(aggregate: TradingBook) {
        require(aggregate.version == 0L && aggregate.deletionGeneration == 0L && aggregate.status == TradingLifecycleStatus.ACTIVE) { "Only a new active TradingBook can be inserted" }
        val parentActive = jdbc.query("""SELECT status FROM trading_accounts WHERE id=? AND owner_member_id=?
            AND venue=? FOR UPDATE""", { rs, _ -> rs.getString(1) }, aggregate.accountId.value,
            aggregate.owner.value, aggregate.venue.name).firstOrNull()
        require(parentActive == "ACTIVE") { "Book requires an active account with matching ownership and venue" }
        val record = TradingBookPersistenceMapper.toRecord(aggregate)
        sql.update("""INSERT INTO trading_books (id, account_id, owner_member_id, venue, product_family, position_mode, settlement_asset, review_timezone, display_name, status, deletion_generation, created_at, updated_at, version, latest_ledger_revision_id)
            VALUES (:id, :accountId, :ownerMemberId, :venue, :productFamily, :positionMode, :settlementAsset, :reviewTimezone, :displayName, :status, :deletionGeneration, :createdAt, :updatedAt, :version, :latestLedgerRevisionId)""", parameters(record))
    }

    override fun find(id: TradingBookId, owner: MemberId): TradingBook? = find(id, owner, false)

    override fun find(id: TradingBookId, owner: MemberId, forUpdate: Boolean): TradingBook? {
        if (forUpdate) check(jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) { "Command lock requires an enclosing transaction" }
        return sql.query("SELECT * FROM trading_books WHERE id=:id AND owner_member_id=:owner" + if (forUpdate) " FOR UPDATE" else "",
            mapOf("id" to id.value, "owner" to owner.value), { rs, _ -> TradingBookPersistenceMapper.toDomain(record(rs)) }).firstOrNull()
    }

    override fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int): TradingPage<TradingBook> {
        val parameters = mapOf("owner" to owner.value, "size" to size, "offset" to page.toLong() * size, "account" to accountId.value)
        val total = sql.queryForObject("SELECT count(*) FROM trading_books WHERE account_id=:account AND owner_member_id=:owner AND status='ACTIVE'", parameters, Long::class.java) ?: 0L
        val items = sql.query("""SELECT * FROM trading_books WHERE account_id=:account AND owner_member_id=:owner AND status='ACTIVE'
            ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset""", parameters,
            { rs, _ -> TradingBookPersistenceMapper.toDomain(record(rs)) })
        return TradingPage(items, page, size, total)
    }

    @Transactional
    override fun update(aggregate: TradingBook): Boolean {
        val record = TradingBookPersistenceMapper.toRecord(aggregate)
        return sql.update("""UPDATE trading_books SET display_name=:displayName, status=:status,
            deletion_generation=:deletionGeneration, updated_at=:updatedAt, version=version+1
            WHERE id=:id AND version=:version
            AND account_id = :accountId
            AND owner_member_id = :ownerMemberId
            AND venue = :venue
            AND product_family = :productFamily
            AND position_mode = :positionMode
            AND settlement_asset = :settlementAsset
            AND review_timezone = :reviewTimezone
            AND created_at = :createdAt
            AND latest_ledger_revision_id IS NOT DISTINCT FROM :latestLedgerRevisionId
            AND EXISTS (SELECT 1 FROM trading_accounts a WHERE a.id=trading_books.account_id
                AND a.owner_member_id=trading_books.owner_member_id AND a.status='ACTIVE')
            AND status IN ('ACTIVE','ARCHIVED')
            AND ((status=:status AND deletion_generation=:deletionGeneration) OR
                (:status='DELETION_PENDING' AND deletion_generation+1=:deletionGeneration))
            AND updated_at <= :updatedAt""", parameters(record)) == 1
    }

    override fun deleted(id: TradingBookId, owner: MemberId): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM trading_deleted_resources WHERE target_type=? AND target_id=? AND owner_member_id=?)",
        Boolean::class.java, "TRADING_BOOK", id.value, owner.value) == true

    private fun record(rs: ResultSet) = TradingBookRecord(
        id = rs.getLong("id"),
        accountId = rs.getLong("account_id"),
        ownerMemberId = rs.getLong("owner_member_id"),
        venue = rs.getString("venue"),
        productFamily = rs.getString("product_family"),
        positionMode = rs.getString("position_mode"),
        settlementAsset = rs.getString("settlement_asset"),
        reviewTimezone = rs.getString("review_timezone"),
        displayName = rs.getString("display_name"),
        status = rs.getString("status"),
        deletionGeneration = rs.getLong("deletion_generation"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        version = rs.getLong("version"),
        latestLedgerRevisionId = rs.getObject("latest_ledger_revision_id", UUID::class.java),
    )

    private fun parameters(record: TradingBookRecord) = MapSqlParameterSource()
        .addValue("id", record.id)
        .addValue("accountId", record.accountId)
        .addValue("ownerMemberId", record.ownerMemberId)
        .addValue("venue", record.venue)
        .addValue("productFamily", record.productFamily)
        .addValue("positionMode", record.positionMode)
        .addValue("settlementAsset", record.settlementAsset)
        .addValue("reviewTimezone", record.reviewTimezone)
        .addValue("displayName", record.displayName)
        .addValue("status", record.status)
        .addValue("deletionGeneration", record.deletionGeneration)
        .addValue("createdAt", Timestamp.from(record.createdAt))
        .addValue("updatedAt", Timestamp.from(record.updatedAt))
        .addValue("version", record.version)
        .addValue("latestLedgerRevisionId", record.latestLedgerRevisionId)
}
