package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingIdempotencyStoreAdapter(private val jdbc: JdbcTemplate) : TradingIdempotencyStore {
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

}
