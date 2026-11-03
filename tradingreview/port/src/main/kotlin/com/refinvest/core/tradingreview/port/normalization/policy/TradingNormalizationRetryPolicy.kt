package com.refinvest.core.tradingreview.port.normalization.policy

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import java.io.InputStream
import java.time.Duration
import java.time.Instant

data class TradingNormalizationRetryPolicy(
    val version: String,
    val maxAttempts: Int,
    val maxElapsedTime: Duration,
    val initialDelay: Duration,
    val maxDelay: Duration,
    val jitter: String,
)
