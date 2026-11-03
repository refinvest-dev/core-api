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

data class NormalizationResultDescriptor(
    val jobId: String,
    val logicalJobId: String,
    val runId: String,
    val payloadId: String,
    val byteSize: Long,
    val payloadSha256: String,
    val attemptToken: String,
    val deletionGeneration: Long,
    val inputSetHash: String,
    val inputHashSpecificationVersion: String,
    val targetVersionSet: Map<String, Any?>,
    val resultSchemaVersion: String,
    val resultHashSpecificationVersion: String,
    val counts: Map<String, Any?>,
    val sectionDigests: List<Any?>,
    val reconciliationManifestHash: String,
    val resultHash: String,
    val expiresAt: Instant,
    val resultDeliveryPolicyVersion: String,
    val contentEncoding: String,
)
