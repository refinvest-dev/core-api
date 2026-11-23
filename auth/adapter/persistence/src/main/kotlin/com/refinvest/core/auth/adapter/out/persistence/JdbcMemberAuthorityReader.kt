package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.port.outbound.authority.AuthorityStateUnavailableException
import com.refinvest.core.auth.port.outbound.authority.MemberAuthorityLookup
import com.refinvest.core.auth.port.outbound.authority.MemberAuthorityReader
import com.refinvest.core.auth.port.outbound.authority.MemberAuthoritySnapshot
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

class JdbcMemberAuthorityReader(private val primary: DataSource, private val queryTimeoutSeconds: Int) : MemberAuthorityReader {
    init {
        require(queryTimeoutSeconds > 0) { "Finite authority query timeout required" }
    }

    override fun check(lookup: MemberAuthorityLookup): MemberAuthoritySnapshot {
        try {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            primary.connection.use { connection ->
                check(connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
                connection.prepareStatement(SQL).use { statement ->
                    statement.queryTimeout = queryTimeoutSeconds
                    val d = lookup.delegation
                    statement.setLong(1, d.subject)
                    statement.setString(2, d.actor)
                    statement.setString(3, d.issuer)
                    statement.setString(4, d.keyId)
                    statement.setString(5, d.issuer)
                    statement.setString(6, lookup.tokenIdSha256)
                    statement.setLong(7, d.issuedAt)
                    statement.setLong(8, d.notBefore)
                    statement.setLong(9, d.expiresAt)
                    statement.executeQuery().use { row ->
                        if (!row.next() || !row.getBoolean("initialized") || !row.getBoolean("primary_node"))
                            throw AuthorityStateUnavailableException()
                        val active = row.getBoolean("active")
                        val revision = row.getLong("revision")
                        if (active && (row.wasNull() || revision <= 0)) throw AuthorityStateUnavailableException()
                        val result = MemberAuthoritySnapshot(active, row.getTimestamp("checked_at").toInstant(), if (active) revision else null)
                        if (row.next()) throw AuthorityStateUnavailableException()
                        return result
                    }
                }
            }
        } catch (_: Exception) {
            throw AuthorityStateUnavailableException()
        }
    }

    companion object {
        private val SQL = """
            SELECT p.schema_version=1 AND p.ready AS initialized,
                   NOT pg_is_in_recovery() AS primary_node,
                   statement_timestamp() AS checked_at, a.revision,
                   COALESCE(m.id IS NOT NULL AND a.allowed AND c.enabled AND c.ingress
                       AND q.enabled AND q.authority AND k.enabled AND r.jti_sha256 IS NULL
                       AND v.iat>0 AND v.nbf=v.iat AND v.exp>v.iat AND v.exp-v.iat<=60
                       AND v.iat<=extract(epoch FROM statement_timestamp())+5
                       AND v.exp>extract(epoch FROM statement_timestamp())-5, FALSE) AS active
              FROM auth_authority_policy p
              LEFT JOIN members m ON m.id=?
              LEFT JOIN auth_member_access a ON a.member_id=m.id
              LEFT JOIN auth_authority_callers c ON c.caller_id=?
              LEFT JOIN auth_authority_callers q ON q.caller_id='trading-review-api'
              LEFT JOIN auth_authority_keys k ON k.issuer=? AND k.kid=?
              LEFT JOIN auth_authority_revocations r ON r.issuer=? AND r.jti_sha256=?
              CROSS JOIN (SELECT ?::bigint AS iat, ?::bigint AS nbf, ?::bigint AS exp) v
             WHERE p.singleton=1
        """.trimIndent()
    }
}
