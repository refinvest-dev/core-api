package com.refinvest.core.config

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationRetryPolicy
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationSettings

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ConfiguredTradingNormalizationSettings(
    @Value("\${refinvest.trading-review.normalization.retry.policy-version:TRADING_NORMALIZATION_RETRY_V1}") private val version: String,
    @Value("\${refinvest.trading-review.normalization.retry.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${refinvest.trading-review.normalization.retry.max-elapsed-time:PT30M}") private val maxElapsed: Duration,
    @Value("\${refinvest.trading-review.normalization.retry.initial-delay:PT2S}") private val initialDelay: Duration,
    @Value("\${refinvest.trading-review.normalization.retry.max-delay:PT1M}") private val maxDelay: Duration,
    @Value("\${refinvest.trading-review.normalization.retry.jitter:FULL_JITTER}") private val jitter: String,
    @Value("\${refinvest.trading-review.normalization.instrument-terms-json:}") private val termsJson: String,
    @Value("\${refinvest.trading-review.normalization.delivery.max-accepted-payload-bytes:52428800}")
    private val maxAcceptedPayloadBytes: Long,
) : TradingNormalizationSettings {
    override fun retryPolicy(): TradingNormalizationRetryPolicy {
        if (version.isBlank() || maxAttempts < 1 || maxElapsed.isNegative || maxElapsed.isZero ||
            initialDelay.isNegative || initialDelay.isZero || maxDelay < initialDelay ||
            jitter !in setOf("NONE", "FULL_JITTER"))
            throw TradingFailure("RETRY_POLICY_UNAVAILABLE", TradingFailureKind.DEPENDENCY_UNAVAILABLE)
        return TradingNormalizationRetryPolicy(version, maxAttempts, maxElapsed, initialDelay, maxDelay, jitter)
    }
    override fun instrumentTermsJson(): String = termsJson.ifBlank {
        throw TradingFailure("INSTRUMENT_TERMS_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED)
    }
    override fun resultDeliveryPolicy(): NormalizationResultDeliveryPolicy = try {
        NormalizationResultDeliveryPolicy("RESULT_DELIVERY_V1", "SINGLE_RESOURCE",
            maxAcceptedPayloadBytes, listOf("identity"))
    } catch (_: IllegalArgumentException) { throw TradingFailure("RESULT_DELIVERY_POLICY_UNAVAILABLE", TradingFailureKind.DEPENDENCY_UNAVAILABLE) }
}
