package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.port.NormalizationResultDescriptor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate

/** Same-transaction durable ACK rows, shared by accepted and deletion-stale publication. */
internal class NormalizationAcknowledgementRows(private val jdbc: JdbcTemplate) {
    fun enqueue(run: NormalizationRun, descriptor: NormalizationResultDescriptor,
                           disposition: String, currentGeneration: Long?, now: Instant) {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(
            "${run.id.value}:${descriptor.jobId}:${descriptor.payloadId}:$disposition".toByteArray(Charsets.UTF_8))
            .let { java.util.HexFormat.of().formatHex(it) }
        jdbc.update("""INSERT INTO trading_normalization_ack_dispatches
            (id,run_id,core_import_session_id,compute_job_id,payload_id,payload_sha256,result_hash,attempt_token,
            deletion_generation,current_deletion_generation,disposition,idempotency_key,retry_policy_json,
            payload_expires_at,status,
            next_attempt_at,created_at,updated_at)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?::uuid,?,?,?,?,?::jsonb,?,'PENDING',?,?,?)
            ON CONFLICT (idempotency_key) DO NOTHING""", UUID.randomUUID().toString(), run.id.value,
            run.input.importSessionId.value,
            descriptor.jobId, descriptor.payloadId, descriptor.payloadSha256, descriptor.resultHash,
            descriptor.attemptToken, descriptor.deletionGeneration, currentGeneration, disposition,
            key, run.input.retryPolicyJson, Timestamp.from(descriptor.expiresAt),
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now))
    }

}
