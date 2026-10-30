package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcArtifactAccessGrants(
    private val jdbc: JdbcTemplate,
    private val storage: RawArtifactStorage,
    private val clock: Clock,
    @Value("\${refinvest.trading-review.grant-base-url:}") private val baseUrl: String,
    @Value("\${REFINVEST_INTERNAL_API_KEY:}") private val secret: String,
) : ArtifactAccessGrantIssuer, ArtifactReadGrantRedeemer {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                                sessionId: TradingImportSessionId, attemptToken: String,
                                deletionGeneration: Long): ArtifactReadGrant {
        if (baseUrl.isBlank() || secret.isBlank()) throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
        val row = jdbc.query("""SELECT a.object_key, a.byte_size, a.content_sha256, a.content_type,
            a.artifact_role FROM trading_source_artifacts a JOIN trading_import_sessions s
            ON s.id=a.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id AND b.owner_member_id=s.owner_member_id
            JOIN trading_accounts c ON c.id=b.account_id AND c.owner_member_id=b.owner_member_id
            JOIN trading_normalization_runs r ON r.id=s.active_normalization_run_id
            JOIN trading_normalization_dispatches d ON d.run_id=r.id
            WHERE a.id=? AND a.import_session_id=? AND d.id=?::uuid
            AND b.status='ACTIVE' AND c.status='ACTIVE'
            AND r.trading_book_id=b.id AND r.owner_member_id=s.owner_member_id
            AND s.status='VALIDATING' AND NOT s.deletion_pending AND
            s.deletion_generation=? AND r.deletion_generation=? AND r.attempt_token=?::uuid AND
            a.retention_status='ACTIVE' AND a.raw_expires_at>? AND a.deleted_at IS NULL""",
            { rs, _ -> listOf(rs.getString("object_key"), rs.getString("byte_size"),
                rs.getString("content_sha256"), rs.getString("content_type"), rs.getString("artifact_role")) },
            artifact.artifactId.value, sessionId.value, logicalJobId, deletionGeneration,
            deletionGeneration, attemptToken, Timestamp.from(clock.instant())).firstOrNull()
            ?: throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", 410)
        if (row[1]?.toLong() != artifact.contentLength || row[2] != artifact.sha256 ||
            row[3] != artifact.mediaType || row[4] != artifact.role.name)
            throw TradingFailure("ARTIFACT_IDENTITY_CONFLICT", 409)
        if (!storage.exists(row[0] ?: throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", 410)))
            throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", 410)
        val nonce = ByteArray(32).also(random::nextBytes)
        val part = encoder.encodeToString(nonce)
        val token = part + "." + encoder.encodeToString(mac(part))
        val now = clock.instant()
        val expiry = now.plus(Duration.ofMinutes(5))
        jdbc.update("""INSERT INTO trading_artifact_read_grants
            (token_hash,artifact_id,logical_job_id,session_id,artifact_role,raw_sha256,
            attempt_token,deletion_generation,expires_at,created_at)
            VALUES (?,?,?::uuid,?,?,?,?::uuid,?,?,?)""",
            sha(token), artifact.artifactId.value, logicalJobId, sessionId.value,
            artifact.role.name, artifact.sha256, attemptToken, deletionGeneration,
            Timestamp.from(expiry), Timestamp.from(now))
        return ArtifactReadGrant(artifact.artifactId.value.toString(), artifact.role.name,
            artifact.sha256, artifact.contentLength, artifact.mediaType,
            baseUrl.trimEnd('/') + "/internal/trading-review/artifact-grants/" + token, expiry)
    }

    override fun redeem(token: String): ArtifactRedemption {
        if (secret.isBlank() || token.length !in 80..120) throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val parts = token.split('.')
        if (parts.size != 2 || !MessageDigest.isEqual(
                try { Base64.getUrlDecoder().decode(parts[1]) } catch (_: Exception) { byteArrayOf() },
                mac(parts[0]))) throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val now = clock.instant()
        val rows = jdbc.query("""UPDATE trading_artifact_read_grants g SET consumed_at=?
            FROM trading_source_artifacts a, trading_import_sessions s,
                 trading_books b, trading_accounts c,
                 trading_normalization_dispatches d, trading_normalization_runs r
            WHERE g.token_hash=? AND g.consumed_at IS NULL AND g.expires_at>? AND
            a.id=g.artifact_id AND s.id=g.session_id AND a.import_session_id=s.id AND
            b.id=s.trading_book_id AND b.owner_member_id=s.owner_member_id AND
            c.id=b.account_id AND c.owner_member_id=b.owner_member_id AND
            b.status='ACTIVE' AND c.status='ACTIVE' AND
            d.id=g.logical_job_id AND r.id=d.run_id AND r.import_session_id=s.id AND
            r.trading_book_id=b.id AND r.owner_member_id=s.owner_member_id AND
            r.attempt_token=g.attempt_token AND r.deletion_generation=g.deletion_generation AND
            s.active_normalization_run_id=r.id AND s.deletion_generation=g.deletion_generation AND
            s.status='VALIDATING' AND NOT s.deletion_pending AND
            a.retention_status='ACTIVE' AND a.raw_expires_at>? AND
            a.content_sha256=g.raw_sha256 AND a.artifact_role=g.artifact_role
            RETURNING a.id,a.object_key,a.wrapped_dek,a.key_version,a.byte_size,a.content_type""",
            { rs, _ -> Descriptor(TradingSourceArtifactId(rs.getLong("id")), rs.getString("object_key"),
                rs.getBytes("wrapped_dek"), rs.getString("key_version"), rs.getLong("byte_size"),
                rs.getString("content_type")) }, Timestamp.from(now), sha(token), Timestamp.from(now),
            Timestamp.from(now))
        val row = rows.firstOrNull() ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        return ArtifactRedemption(storage.open(row.key, row.dek, row.keyVersion, row.id), row.length, row.mediaType)
    }

    private fun mac(text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(text.toByteArray(Charsets.US_ASCII))
    }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    private data class Descriptor(val id: TradingSourceArtifactId, val key: String, val dek: ByteArray,
                                  val keyVersion: String, val length: Long, val mediaType: String)
}
