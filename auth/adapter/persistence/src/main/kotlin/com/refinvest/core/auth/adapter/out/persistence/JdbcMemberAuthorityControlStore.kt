package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.port.outbound.authority.AuthorityStateUnavailableException
import com.refinvest.core.auth.port.outbound.authority.MemberAuthorityControlStore
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class JdbcMemberAuthorityControlStore(private val primary: DataSource) : MemberAuthorityControlStore {
    private val jdbc = JdbcTemplate(primary)

    override fun registerMember(memberId: MemberId): Boolean {
        require(memberId.value > 0)
        return update("INSERT INTO auth_member_access(member_id) VALUES (?) ON CONFLICT DO NOTHING", memberId.value)
    }

    override fun setMemberAllowed(memberId: MemberId, expectedRevision: Long, allowed: Boolean): Boolean {
        require(memberId.value > 0 && validRevision(expectedRevision))
        return update("UPDATE auth_member_access SET allowed=?,revision=revision+1 WHERE member_id=? AND revision=?", allowed, memberId.value, expectedRevision)
    }

    override fun registerCaller(callerId: String, ingress: Boolean, authority: Boolean): Boolean {
        require(callerPattern.matches(callerId))
        return update("INSERT INTO auth_authority_callers(caller_id,ingress,authority) VALUES (?,?,?) ON CONFLICT DO NOTHING", callerId, ingress, authority)
    }

    override fun setCallerEnabled(callerId: String, expectedRevision: Long, enabled: Boolean): Boolean {
        require(callerPattern.matches(callerId) && validRevision(expectedRevision))
        return update("UPDATE auth_authority_callers SET enabled=?,revision=revision+1 WHERE caller_id=? AND revision=?", enabled, callerId, expectedRevision)
    }

    override fun registerKey(issuer: String, keyId: String): Boolean {
        require(validText(issuer, 512) && validText(keyId, 128))
        return update("INSERT INTO auth_authority_keys(issuer,kid) VALUES (?,?) ON CONFLICT DO NOTHING", issuer, keyId)
    }

    override fun setKeyEnabled(issuer: String, keyId: String, expectedRevision: Long, enabled: Boolean): Boolean {
        require(validText(issuer, 512) && validText(keyId, 128) && validRevision(expectedRevision))
        return update("UPDATE auth_authority_keys SET enabled=?,revision=revision+1 WHERE issuer=? AND kid=? AND revision=?", enabled, issuer, keyId, expectedRevision)
    }

    override fun revokeToken(issuer: String, tokenIdSha256: String, retainUntil: Instant) {
        require(validText(issuer, 512) && hashPattern.matches(tokenIdSha256))
        update("""INSERT INTO auth_authority_revocations(issuer,jti_sha256,retain_until) VALUES (?,?,?)
            ON CONFLICT(issuer,jti_sha256) DO UPDATE
            SET retain_until=GREATEST(auth_authority_revocations.retain_until,EXCLUDED.retain_until)""".trimIndent(), issuer, tokenIdSha256, Timestamp.from(retainUntil))
    }

    private fun update(sql: String, vararg args: Any): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive() &&
            TransactionSynchronizationManager.hasResource(primary) && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            "Authority controls require the primary write transaction"
        }
        try {
            return jdbc.update(sql, *args) == 1
        } catch (_: Exception) {
            throw AuthorityStateUnavailableException()
        }
    }

    private fun validRevision(value: Long): Boolean = value in 1 until Long.MAX_VALUE

    private fun validText(value: String, max: Int): Boolean = value.length in 1..max && value.isNotBlank() &&
        value.none { Character.isISOControl(it) } && Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray().size <= max

    companion object {
        private val callerPattern = Regex("[a-z][a-z0-9-]{0,63}")
        private val hashPattern = Regex("[0-9a-f]{64}")
    }
}
