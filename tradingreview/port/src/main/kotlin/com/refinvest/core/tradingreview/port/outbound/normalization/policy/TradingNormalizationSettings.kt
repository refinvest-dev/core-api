package com.refinvest.core.tradingreview.port.outbound.normalization.policy

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.*

interface TradingNormalizationSettings {
    fun retryPolicy(): TradingNormalizationRetryPolicy
    fun instrumentTermsJson(): String
    fun resultDeliveryPolicy(): NormalizationResultDeliveryPolicy
}
