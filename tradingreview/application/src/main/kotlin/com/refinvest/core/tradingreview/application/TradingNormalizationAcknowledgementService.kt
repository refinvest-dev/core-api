package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.inbound.normalization.acknowledge.AcknowledgeTradingNormalizationResultUseCase
import com.refinvest.core.tradingreview.port.outbound.compute.result.NormalizationAckOutcome
import com.refinvest.core.tradingreview.port.outbound.compute.result.TradingNormalizationResultClient
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingRetryJitter
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationResultStore

import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.math.min
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

/** Delivers the committed ACK outbox without repeating result verification or publication. */
@Service
class TradingNormalizationAcknowledgementService(
    private val results: TradingNormalizationResultStore,
    private val compute: TradingNormalizationResultClient,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val jitter: TradingRetryJitter,
) : AcknowledgeTradingNormalizationResultUseCase {
    override fun acknowledgeOne(): Boolean {
        val worker = UUID.randomUUID().toString()
        val ack = transactions.execute { results.claimAck(clock.instant(), Duration.ofSeconds(45), worker) }
            ?: return false
        val outcome = compute.acknowledge(ack)
        transactions.execute {
            val now = clock.instant()
            when (outcome) {
                NormalizationAckOutcome.Accepted -> results.completeAck(ack.idempotencyKey, worker, now)
                is NormalizationAckOutcome.Rejected -> results.failAck(ack.idempotencyKey, worker,
                    outcome.code, now)
                NormalizationAckOutcome.Retry -> {
                    val pinned = mapper.readTree(ack.retryPolicyJson)
                    val attempts = pinned.path("maxAttempts").asInt()
                    val deadline = minOf(ack.payloadExpiresAt,
                        ack.occurredAt.plus(Duration.parse(pinned.path("maxElapsedTime").asText())))
                    if (ack.attemptCount >= attempts || !now.isBefore(deadline)) {
                        results.failAck(ack.idempotencyKey, worker, "RESULT_NOT_AVAILABLE", now)
                        return@execute
                    }
                    val initial = Duration.parse(pinned.path("initialDelay").asText())
                    val maximum = Duration.parse(pinned.path("maxDelay").asText())
                    val scaled = min(initial.toMillis().toDouble() *
                        (1L shl min(ack.attemptCount - 1, 20)), maximum.toMillis().toDouble())
                    val factor = if (pinned.path("jitter").asText() == "FULL_JITTER") jitter.factor() else 1.0
                    val next = now.plusMillis((scaled * factor).toLong().coerceAtLeast(0))
                    if (!next.isBefore(deadline))
                        results.failAck(ack.idempotencyKey, worker, "RESULT_NOT_AVAILABLE", now)
                    else results.retryAck(ack.idempotencyKey, worker, next, now)
                }
            }
        }
        return true
    }
}
