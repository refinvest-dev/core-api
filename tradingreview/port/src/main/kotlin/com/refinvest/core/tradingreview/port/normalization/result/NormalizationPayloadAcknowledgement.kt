package com.refinvest.core.tradingreview.port.normalization.result

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

data class NormalizationPayloadAcknowledgement(
    val runId: String,
    val coreImportSessionId: String,
    val jobId: String,
    val payloadId: String,
    val payloadSha256: String,
    val resultHash: String,
    val attemptToken: String,
    val deletionGeneration: Long,
    val idempotencyKey: String,
    val disposition: String,
    val occurredAt: Instant,
    val currentDeletionGeneration: Long? = null,
    val attemptCount: Int = 0,
    val retryPolicyJson: String,
    val payloadExpiresAt: Instant,
)
