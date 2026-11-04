package com.refinvest.core.tradingreview.application.normalization

import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationRetryPolicy
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingRetryJitter
import java.time.Duration
import kotlin.math.min
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Reads the pinned retry policy and computes bounded delays without choosing a current time. */
@Component
class NormalizationRetrySchedule(private val mapper: ObjectMapper, private val jitter: TradingRetryJitter) {
    fun delay(policy: TradingNormalizationRetryPolicy, count: Int, retryAfter: Duration?): Duration {
        val scaled = policy.initialDelay.toMillis().toDouble() * (1L shl min(count - 1, 20))
        val capped = min(scaled, policy.maxDelay.toMillis().toDouble())
        val withJitter = if (policy.jitter == "FULL_JITTER") capped * jitter.factor() else capped
        return Duration.ofMillis(maxOf(withJitter.toLong(), retryAfter?.toMillis() ?: 0L, 0L))
    }

    fun policy(run: NormalizationRun): TradingNormalizationRetryPolicy {
        val pinned = mapper.readTree(run.input.retryPolicyJson)
        return TradingNormalizationRetryPolicy(
            pinned.path("policyVersion").asText(), pinned.path("maxAttempts").asInt(),
            Duration.parse(pinned.path("maxElapsedTime").asText()),
            Duration.parse(pinned.path("initialDelay").asText()),
            Duration.parse(pinned.path("maxDelay").asText()), pinned.path("jitter").asText())
    }

}
