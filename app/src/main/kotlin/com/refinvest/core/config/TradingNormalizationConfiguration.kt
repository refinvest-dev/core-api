package com.refinvest.core.config

import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingNormalizationRetryPolicy
import com.refinvest.core.tradingreview.port.TradingNormalizationSettings
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class ConfiguredTradingNormalizationSettings(
    @Value("\${refinvest.trading-review.normalization.retry.policy-version:TRADING_NORMALIZATION_RETRY_V1}") private val version: String,
    @Value("\${refinvest.trading-review.normalization.retry.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${refinvest.trading-review.normalization.retry.max-elapsed-time:PT30M}") private val maxElapsed: Duration,
    @Value("\${refinvest.trading-review.normalization.retry.initial-delay:PT2S}") private val initialDelay: Duration,
    @Value("\${refinvest.trading-review.normalization.retry.max-delay:PT1M}") private val maxDelay: Duration,
    @Value("\${refinvest.trading-review.normalization.retry.jitter:FULL_JITTER}") private val jitter: String,
    @Value("\${refinvest.trading-review.normalization.instrument-terms-json:}") private val termsJson: String,
) : TradingNormalizationSettings {
    override fun retryPolicy(): TradingNormalizationRetryPolicy {
        if (version.isBlank() || maxAttempts < 1 || maxElapsed.isNegative || maxElapsed.isZero ||
            initialDelay.isNegative || initialDelay.isZero || maxDelay < initialDelay ||
            jitter !in setOf("NONE", "FULL_JITTER"))
            throw TradingFailure("RETRY_POLICY_UNAVAILABLE", 503)
        return TradingNormalizationRetryPolicy(version, maxAttempts, maxElapsed, initialDelay, maxDelay, jitter)
    }
    override fun instrumentTermsJson(): String = termsJson.ifBlank {
        throw TradingFailure("INSTRUMENT_TERMS_UNAVAILABLE", 422)
    }
}
