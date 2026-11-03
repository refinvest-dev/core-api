package com.refinvest.core.tradingreview.port.normalization.compute

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.*
import java.time.Instant

data class NormalizationJobStatus(
    val jobId: String, val state: String, val attemptToken: String,
    val deletionGeneration: Long, val grantRevision: Long,
    val expectedInputSetHash: String, val versionSetHash: String,
    val grantAction: String, val grantRefreshDeadlineAt: Instant?,
    val failureCode: String?,
    val resultVersionPin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
)
