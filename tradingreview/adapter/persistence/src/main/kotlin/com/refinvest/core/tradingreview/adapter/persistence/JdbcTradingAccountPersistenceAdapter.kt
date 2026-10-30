package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.account.TradingAccountReader
import com.refinvest.core.tradingreview.port.account.TradingAccountStore
import java.sql.ResultSet
import java.sql.Timestamp
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcTradingAccountPersistenceAdapter(private val jdbc: JdbcTemplate) : TradingAccountStore, TradingAccountReader {
    private val sql = NamedParameterJdbcTemplate(jdbc)

    @Transactional
    override fun insert(aggregate: TradingAccount) {
        require(aggregate.version == 0L && aggregate.deletionGeneration == 0L && aggregate.status == TradingLifecycleStatus.ACTIVE) { "Only a new active TradingAccount can be inserted" }
        val record = TradingAccountPersistenceMapper.toRecord(aggregate)
        sql.update("""INSERT INTO trading_accounts (id, owner_member_id, venue, display_name, status, deletion_generation, created_at, updated_at, version)
            VALUES (:id, :ownerMemberId, :venue, :displayName, :status, :deletionGeneration, :createdAt, :updatedAt, :version)""", parameters(record))
    }

    override fun find(id: TradingAccountId, owner: MemberId): TradingAccount? = find(id, owner, false)

    override fun find(id: TradingAccountId, owner: MemberId, forUpdate: Boolean): TradingAccount? {
        if (forUpdate) check(jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) { "Command lock requires an enclosing transaction" }
        return sql.query("SELECT * FROM trading_accounts WHERE id=:id AND owner_member_id=:owner" + if (forUpdate) " FOR UPDATE" else "",
            mapOf("id" to id.value, "owner" to owner.value), { rs, _ -> TradingAccountPersistenceMapper.toDomain(record(rs)) }).firstOrNull()
    }

    override fun list(owner: MemberId, page: Int, size: Int): TradingPage<TradingAccount> {
        val parameters = mapOf("owner" to owner.value, "size" to size, "offset" to page.toLong() * size)
        val total = sql.queryForObject("SELECT count(*) FROM trading_accounts WHERE owner_member_id=:owner AND status IN ('ACTIVE','ARCHIVED')", parameters, Long::class.java) ?: 0L
        val items = sql.query("""SELECT * FROM trading_accounts WHERE owner_member_id=:owner AND status IN ('ACTIVE','ARCHIVED')
            ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset""", parameters,
            { rs, _ -> TradingAccountPersistenceMapper.toDomain(record(rs)) })
        return TradingPage(items, page, size, total)
    }

    @Transactional
    override fun update(aggregate: TradingAccount): Boolean {
        val record = TradingAccountPersistenceMapper.toRecord(aggregate)
        return sql.update("""UPDATE trading_accounts SET display_name=:displayName, status=:status,
            deletion_generation=:deletionGeneration, updated_at=:updatedAt, version=version+1
            WHERE id=:id AND version=:version
            AND owner_member_id = :ownerMemberId
            AND venue = :venue
            AND created_at = :createdAt
            AND status IN ('ACTIVE','ARCHIVED')
            AND ((status=:status AND deletion_generation=:deletionGeneration) OR
                (:status='DELETION_PENDING' AND deletion_generation+1=:deletionGeneration))
            AND updated_at <= :updatedAt""", parameters(record)) == 1
    }

    override fun deleted(id: TradingAccountId, owner: MemberId): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM trading_deleted_resources WHERE target_type=? AND target_id=? AND owner_member_id=?)",
        Boolean::class.java, "TRADING_ACCOUNT", id.value, owner.value) == true

    private fun record(rs: ResultSet) = TradingAccountRecord(
        id = rs.getLong("id"),
        ownerMemberId = rs.getLong("owner_member_id"),
        venue = rs.getString("venue"),
        displayName = rs.getString("display_name"),
        status = rs.getString("status"),
        deletionGeneration = rs.getLong("deletion_generation"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        version = rs.getLong("version"),
    )

    private fun parameters(record: TradingAccountRecord) = MapSqlParameterSource()
        .addValue("id", record.id)
        .addValue("ownerMemberId", record.ownerMemberId)
        .addValue("venue", record.venue)
        .addValue("displayName", record.displayName)
        .addValue("status", record.status)
        .addValue("deletionGeneration", record.deletionGeneration)
        .addValue("createdAt", Timestamp.from(record.createdAt))
        .addValue("updatedAt", Timestamp.from(record.updatedAt))
        .addValue("version", record.version)
}
